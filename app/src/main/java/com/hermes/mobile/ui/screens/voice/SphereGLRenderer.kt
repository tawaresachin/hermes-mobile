package com.hermes.mobile.ui.screens.voice

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU voice-sphere renderer — neon blue/magenta energy sphere whose interior is
 * SWIRLING PLASMA FLARES (simplex-noise, organic, non-repeating) on a dark
 * blue body with a luminous blue/cyan -> magenta rim. Matches the user's
 * reference tones; no regular geometric pattern, no white clipping.
 *   - uAudioVolume swells the flares (capped, 0 at silence -> base intact)
 *   - hardened: shader failure falls back to a solid dark disc (no crash)
 */
class SphereGLRenderer : GLSurfaceView.Renderer {

    @Volatile private var uAudioVolume = 0f

    private var program = 0
    private var uTimeLoc = -1
    private var uVolumeLoc = -1
    private var quadBuffer: FloatBuffer? = null
    private var startNanos = 0L

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

        vec4 permute(vec4 x){return mod(((x*34.0)+1.0)*x, 289.0);}
        vec4 taylorInvSqrt(vec4 r){return 1.79284291400159 - 0.85373472095314 * r;}

        float snoise(vec3 v){
          const vec2 C = vec2(1.0/6.0, 1.0/3.0);
          const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
          vec3 i  = floor(v + dot(v, C.yyy));
          vec3 x0 = v - i + dot(i, C.xxx);
          vec3 g = step(x0.yzx, x0.xyz);
          vec3 l = 1.0 - g;
          vec3 i1 = min(g.xyz, l.zxy);
          vec3 i2 = max(g.xyz, l.zxy);
          vec3 x1 = x0 - i1 + 1.0 * C.xxx;
          vec3 x2 = x0 - i2 + 2.0 * C.xxx;
          vec3 x3 = x0 - D.yyy;
          i = mod(i, 289.0);
          vec4 p = permute(permute(permute(
                     i.z + vec4(0.0, i1.z, i2.z, 1.0))
                   + i.y + vec4(0.0, i1.y, i2.y, 1.0))
                   + i.x + vec4(0.0, i1.x, i2.x, 1.0));
          float n_ = 0.142857142857;
          vec3 ns = n_ * D.wyz - D.xzx;
          vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
          vec4 x_ = floor(j * ns.z);
          vec4 y_ = floor(j - 7.0 * x_);
          vec4 x = x_ * ns.x + ns.yyyy;
          vec4 y = y_ * ns.x + ns.yyyy;
          vec4 h = 1.0 - abs(x) - abs(y);
          vec4 b0 = vec4(x.xy, y.xy);
          vec4 b1 = vec4(x.zw, y.zw);
          vec4 s0 = floor(b0)*2.0 + 1.0;
          vec4 s1 = floor(b1)*2.0 + 1.0;
          vec4 sh = -step(h, vec4(0.0));
          vec4 a0 = b0.xzyw + s0.xzyw*sh.xxyy;
          vec4 a1 = b1.xzyw + s1.xzyw*sh.zzww;
          vec3 p0 = vec3(a0.xy,h.x);
          vec3 p1 = vec3(a0.zw,h.y);
          vec3 p2 = vec3(a1.xy,h.z);
          vec3 p3 = vec3(a1.zw,h.w);
          vec4 norm = taylorInvSqrt(vec4(dot(p0,p0), dot(p1,p1), dot(p2, p2), dot(p3,p3)));
          p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
          vec4 m = max(0.6 - vec4(dot(x0,x0), dot(x1,x1), dot(x2,x2), dot(x3,x3)), 0.0);
          m = m * m;
          return 42.0 * dot(m*m, vec4(dot(p0,x0), dot(p1,x1), dot(p2,x2), dot(p3,x3)));
        }

        void main() {
            float d = length(vTexCoord);
            if (d > 0.99) { discard; }
            float x = vTexCoord.x;
            float y = vTexCoord.y;
            float a = atan(y, x);
            float tm = uTime;

            // swirl-warped coords (tangential energy wind)
            float sw = 2.6;
            float px = x * sw + 0.8 * sin(a + tm * 0.2) * (1.0 - d);
            float py = y * sw - 0.8 * cos(a - tm * 0.2) * (1.0 - d);
            float zz = tm * 0.10;
            float n1 = snoise(vec3(px, py, zz));
            float n2 = snoise(vec3(px * 2.0, py * 2.0, zz + 3.0));
            float n = n1 * 0.65 + n2 * 0.35;

            // soft organic flares at noise peaks
            float flare = smoothstep(0.18, 0.62, abs(n));
            flare *= smoothstep(0.10, 0.35, d) * (1.0 - 0.55 * smoothstep(0.78, 0.99, d));
            flare *= (0.7 + 0.3 * sin(tm * 0.7));            // pulse
            flare *= (0.5 + 0.35 * uAudioVolume);            // voice, capped

            // thin swirl streak for definition
            float twist = a * 3.0 - tm * 0.6;
            float streak = exp(-(pow(sin(twist * 2.0), 2.0)) / (2.0 * 0.35 * 0.35));
            streak *= smoothstep(0.2, 0.5, d) * (1.0 - 0.5 * smoothstep(0.8, 0.99, d));
            flare = clamp(flare + streak * 0.35, 0.0, 1.0);

            // dark blue body
            vec3 body = vec3(0.022, 0.02, 0.05)
                      + vec3(0.14, 0.19, 0.34) * (0.30 * smoothstep(0.2, 0.9, d));

            // blue-left -> magenta-right flare color
            float hue = 0.5 + 0.5 * x;
            vec3 leftC = vec3(0.13, 0.30, 1.00);
            vec3 rightC = vec3(0.40, 0.12, 0.92);
            float flu = clamp(hue + 0.15 * sin(-tm * 0.2), 0.0, 1.0);
            vec3 flare_col = mix(leftC, rightC, flu);
            vec3 flares = flare_col * flare * 1.3;

            // luminous rim (gradient) + soft blue core
            vec3 rim_c = mix(leftC, rightC, hue);
            vec3 rim_glow = rim_c * smoothstep(0.70, 0.99, d) * 2.3;
            vec3 central = vec3(0.03, 0.05, 0.10) * clamp(1.0 - 2.2 * d, 0.0, 1.0) * 1.4;

            vec3 finalColor = body + flares + rim_glow + central;
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
            throw RuntimeException("Shader compile error: " + GLES20.glGetShaderInfoLog(sh))
        }
        return sh
    }
}