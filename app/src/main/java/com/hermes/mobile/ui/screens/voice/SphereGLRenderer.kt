package com.hermes.mobile.ui.screens.voice

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU renderer for the voice sphere — an exact port of the validated
 * fullscreen-quad GLSL fragment shader (simplex-noise wisp folds + magenta
 * fresnel edge ring + dark core), with voice-reactive uniforms:
 *   uTime       — seconds (animates the wisp noise drift)
 *   uAmplitude  — 0..1 voice energy (mic level / TTS wave)
 *   uMode       — 0 idle, 1 listening, 2 speaking, 3 thinking
 */
class SphereGLRenderer : GLSurfaceView.Renderer {

    @Volatile private var uAmplitude = 0f
    @Volatile private var uMode = 0f

    private var program = 0
    private var uTimeLoc = -1
    private var uAmpLoc = -1
    private var uModeLoc = -1
    private var quadBuffer: FloatBuffer? = null
    private var startNanos = 0L

    /** Called from the UI thread; renderer picks it up next frame. */
    fun setVoice(amplitude: Float, mode: Int) {
        uAmplitude = amplitude.coerceIn(0f, 1f)
        uMode = mode.toFloat()
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
        precision mediump float;
        varying vec2 vTexCoord;
        uniform float uTime;
        uniform float uAmplitude;
        uniform float uMode;

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
            if (d > 0.85) { discard; }

            float z = sqrt(0.85 * 0.85 - d * d);
            vec3 sphereNormal = normalize(vec3(vTexCoord, z));

            vec3 brightMagenta = vec3(0.92, 0.25, 0.98);
            vec3 electricBlue  = vec3(0.05, 0.35, 1.0);
            vec3 softPink      = vec3(0.95, 0.45, 0.75);
            vec3 deepBackground = vec3(0.02, 0.0, 0.05);

            float edgeGlow = smoothstep(0.70, 0.85, d);

            // voice-reactive drift: wisps flow faster & fold harder with energy
            float amp = uAmplitude;
            vec3 noisePos1 = vec3(sphereNormal.xy * 1.8, uTime * (0.15 + 0.12 * amp));
            vec3 noisePos2 = vec3(sphereNormal.xy * 3.5, -uTime * 0.1);

            float wisp1 = snoise(noisePos1);
            float wisp2 = snoise(noisePos2 + vec3(wisp1 * (0.5 + 0.35 * amp)));

            float fold1 = smoothstep(0.1 - 0.06 * amp, 0.4, abs(wisp1));
            float fold2 = smoothstep(0.2, 0.5, abs(wisp2));

            vec3 finalColor = deepBackground;
            finalColor += electricBlue * fold1 * (1.0 - d) * (0.7 + 0.55 * amp);
            finalColor += softPink * fold2 * (0.4 + 0.35 * amp);
            finalColor = mix(finalColor, brightMagenta, edgeGlow * (0.9 + 0.2 * amp));

            // thinking mode: cooler, calmer wisps; idle: subtle breathing
            if (uMode > 2.5) {            // THINKING
                finalColor = mix(finalColor, deepBackground, 0.25);
            } else if (uMode < 0.5) {     // IDLE
                finalColor = mix(finalColor, deepBackground, 0.35);
            }

            float opacity = smoothstep(0.85, 0.845, d);
            gl_FragColor = vec4(finalColor, opacity);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
        uAmpLoc = GLES20.glGetUniformLocation(program, "uAmplitude")
        uModeLoc = GLES20.glGetUniformLocation(program, "uMode")
        startNanos = System.nanoTime()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val t = (System.nanoTime() - startNanos) / 1_000_000_000f
        GLES20.glUniform1f(uTimeLoc, t)
        GLES20.glUniform1f(uAmpLoc, uAmplitude)
        GLES20.glUniform1f(uModeLoc, uMode)

        var buf = quadBuffer
        if (buf == null) {
            // fullscreen quad in NDC [-1..1]
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
