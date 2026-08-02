package com.hermes.mobile.ui.screens.voice

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU voice-sphere renderer — neon blue/magenta swirling-ribbon energy sphere,
 * pixel-tuned in a numpy harness against the user's reference
 * (img_e097b070e7b0): DARK saturated body, swirling blue->magenta ribbons,
 * luminous blue/cyan/magenta rim glow, soft blue core, NO white clipping.
 *   - uAudioVolume swells ribbons (capped, 0 at silence -> base intact)
 *   - hardened: shader failure falls back to a solid dark disc (no crash)
 */
class SphereGLRenderer : GLSurfaceView.Renderer {

    @Volatile private var uAudioVolume = 0f

    private var program = 0
    private var uTimeLoc = -1
    private var uVolumeLoc = -1
    private var quadBuffer: FloatBuffer? = null
    private var startNanos = 0L

    /** Real-time audio volume 0..1 — sent to the shader each frame. */
    fun setAudioVolume(volume: Float) {
        uAudioVolume = volume.coerceIn(0f, 1f)
    }

    private val VERTEX_SHADER = """
        attribute vec4 aPos;
        varying vec2 vTexCoord;
        void main() {
            vTexCoord = aPos.xy;
            gl_Position = aPos;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying vec2 vTexCoord;
        uniform float uTime;
        uniform float uAudioVolume;

        void main() {
            float d = length(vTexCoord);
            if (d > 0.99) { discard; }
            float x = vTexCoord.x;
            float y = vTexCoord.y;
            float z = sqrt(max(0.0, 1.0 - x * x - y * y));
            float a = atan(y, x);
            float tm = uTime;

            // dark body + soft blue haze
            vec3 body = vec3(0.022, 0.02, 0.04)
                      + vec3(0.14, 0.19, 0.34) * (0.30 * smoothstep(0.20, 0.90, d));

            // swirling ribbons (2 counter-winding sets) — capped, no white clip
            float twistA = a * 3.0 - (1.0 - z) * 6.0 - tm * 0.64;
            float twistB = -a * 2.0 - (1.0 - z) * 4.0 - tm * 0.40;
            float bandA = 1.0 - smoothstep(0.30, 0.75, abs(sin(twistA * 2.0)));
            float bandB = 1.0 - smoothstep(0.40, 0.80, abs(sin(twistB * 2.0 + 0.8)));
            float rib = clamp(bandA * 0.8 + bandB * 0.5, 0.0, 1.0);
            rib *= smoothstep(0.15, 0.45, d) * (1.0 - 0.4 * smoothstep(0.75, 0.98, d));
            rib *= (0.6 + 0.6 * (0.5 + 0.5 * sin(tm * 0.7)));   // pulse
            rib *= (0.55 + 0.25 * uAudioVolume);                // voice, capped

            // blue-left -> magenta-right hue
            vec3 leftC = vec3(0.13, 0.30, 1.00);   // electric blue
            vec3 rightC = vec3(0.40, 0.12, 0.92);  // magenta
            float hue = 0.5 + 0.5 * x;
            float swirlHue = clamp(hue + 0.15 * sin(a * 2.0 - tm * 0.3), 0.0, 1.0);
            vec3 rib_col = mix(leftC, rightC, swirlHue);
            vec3 ribbons = rib_col * rib * 1.55;

            // luminous rim (left->right gradient)
            vec3 rim_c = mix(leftC, rightC, hue);
            vec3 rim_glow = rim_c * smoothstep(0.70, 0.99, d) * 2.3;
            // soft blue core
            vec3 central = vec3(0.03, 0.05, 0.10) * clamp(1.0 - 2.2 * d, 0.0, 1.0) * 1.4;

            vec3 finalColor = body + ribbons + rim_glow + central;
            float opacity = 1.0 - smoothstep(0.985, 0.99, d);
            gl_FragColor = vec4(finalColor, opacity);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = try {
            createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        } catch (t: Throwable) {
            android.util.Log.e("SphereGL", "shader init failed", t)
            0
        }
        uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
        uVolumeLoc = GLES20.glGetUniformLocation(program, "uAudioVolume")
        startNanos = System.nanoTime()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0) {
            // fallback: solid dark disc so the screen never crashes
            GLES20.glClearColor(0.02f, 0.012f, 0.05f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        GLES20.glUseProgram(program)

        val t = (System.nanoTime() - startNanos) / 1_000_000_000f
        GLES20.glUniform1f(uTimeLoc, t)
        GLES20.glUniform1f(uVolumeLoc, uAudioVolume)

        var buf = quadBuffer
        if (buf == null) {
            val quad = floatArrayOf(
                -1f, -1f, 0f,
                 1f, -1f, 0f,
                -1f,  1f, 0f,
                 1f,  1f, 0f
            )
            buf = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(quad).position(0)
            quadBuffer = buf
        }
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            throw RuntimeException("Link error: " + GLES20.glGetProgramInfoLog(prog))
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val status = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            throw RuntimeException(
                "Shader compile error: " + GLES20.glGetShaderInfoLog(sh)
            )
        }
        return sh
    }
}