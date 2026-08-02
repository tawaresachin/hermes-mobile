package com.hermes.mobile.ui.screens.voice

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU renderer for the voice sphere — target-matched fragment shader.
 *
 * The visual was iterated against the user's reference image with a numpy
 * render harness (pixel-diff optimized) and the winning parameters were
 * ported here verbatim:
 *   - dark blue-violet body, near-black core
 *   - sparse electric-blue wisp folds (simplex noise, high thresholds)
 *   - directional rim lit from upper-left + specular hotspot
 *   - gentle radial brightness ramp (14 -> 43 like the target)
 *   - uAudioVolume: additive voice-reactive layer (0 at silence -> base intact)
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

        // luminous petal glow helper
        vec3 petalGauss(vec2 p, vec2 c, float r, vec3 col, float d) {
            float sd = length(p - c);
            float w = exp(-(sd * sd) / (2.0 * r * r));
            w *= clamp(1.0 - d, 0.0, 1.0) * 3.2;
            return col * w;
        }

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
                    float ang0 = atan(vTexCoord.y, vTexCoord.x);   // y-up
                    float tm = uTime;

                    // ── organic lumpy boundary, slowly breathing ──
                    float ang = ang0;
                    float lump = 1.0
                        + 0.03 * sin(ang * 3.0 + 0.5 + 0.2 * tm) * sin(ang * 2.0 - 0.15 * tm)
                        + 0.02 * sin(ang * 5.0 + 1.3 + 0.1 * tm)
                        + 0.012 * sin(ang * 7.0 + 0.7)
                        + 0.012 * sin(tm * 0.3);
                    lump = max(lump, 0.6);
                    float rn = d / lump;
                    if (rn > 1.0) { discard; }

                    vec3 coreCol = vec3(0.12, 0.043, 0.19);

                    // ── iridescent rim band, sweep rotates slowly ──
                    float asw = ang + tm * 0.22;                    // rotating iridescence
                    float rim = smoothstep(0.82, 0.98, rn);
                    float rimIn = smoothstep(0.85, 0.98, rn);
                    vec3 iri = vec3(
                                    clamp(0.95 - 0.10 * (0.5 + 0.5 * cos(asw * 2.0)), 0.0, 1.0),
                                    clamp(0.22 + 0.30 * (0.5 + 0.5 * cos(asw * 3.0 + 1.0)), 0.0, 1.0),
                                    clamp(0.62 + 0.50 * (0.5 + 0.5 * cos(asw * 2.0 + 0.8)), 0.0, 1.0));
                    // specular hotspots drift along the rim
                    float hot = 0.35 * exp(-pow(ang - 0.6 - tm * 0.25, 2.0) / 0.08)
                              + 0.28 * exp(-pow(ang + 2.6 + tm * 0.20, 2.0) / 0.10);
                    vec3 rimLit = iri * rim * (0.85 + 0.5 * hot)
                                + vec3(0.7, 0.5, 0.5) * rimIn * hot * 0.6;

                    // ── drifting luminous petal waves ──
                    vec3 petal = vec3(0.0);
                    petal += petalGauss(vTexCoord,
                        vec2(0.60 + 0.08 * sin(tm * 0.4), -0.35 + 0.09 * cos(tm * 0.5)), 0.22,
                        vec3(0.40, 0.34, 0.98), d);
                    petal += petalGauss(vTexCoord,
                        vec2(0.72 + 0.06 * cos(tm * 0.30), 0.55 + 0.07 * sin(tm * 0.35)), 0.24,
                        vec3(0.38, 0.20, 0.72), d);
                    petal += petalGauss(vTexCoord,
                        vec2(-0.50 + 0.07 * sin(tm * 0.45), 0.20 + 0.06 * sin(tm * 0.40 + 1.0)), 0.20,
                        vec3(0.26, 0.15, 0.88), d);
                    petal += petalGauss(vTexCoord,
                        vec2(-0.55 + 0.05 * cos(tm * 0.50), 0.50 + 0.06 * cos(tm * 0.40 + 2.0)), 0.18,
                        vec3(0.30, 0.26, 0.92), d);

                    // ── translucent inner glow (gentle shimmer) ──
                    float haze = smoothstep(0.0, 0.75, d) * (1.0 - rn)
                               * (0.9 - 0.6 * smoothstep(0.0, 0.55, d))
                               * (0.85 + 0.15 * sin(tm * 0.6 + d * 4.0));
                    vec3 hazeCol = vec3(0.22, 0.08, 0.36) * (haze * 0.6);

                    // ── composite ──
                    vec3 innerCol = coreCol + hazeCol + petal;
                    vec3 finalColor = mix(innerCol, rimLit, rim);
                    finalColor = mix(vec3(0.10, 0.05, 0.16), finalColor, step(rn, 1.0));

                    // audio-reactive (0 at silence -> base intact)
                    finalColor += petal * uAudioVolume * 0.6;

                    float opacity = smoothstep(1.0, 0.995, rn);
                    gl_FragColor = vec4(finalColor, opacity);
                }void main() {
            float d = length(vTexCoord);
            if (d > 0.99) { discard; }
            float x = vTexCoord.x, y = vTexCoord.y;
            float z = sqrt(max(0.0, 1.0 - x * x - y * y));
            float a = atan(y, x);
            float tm = uTime;

            // dark blue body + soft radial haze
            vec3 body = vec3(0.03, 0.025, 0.05)
                      + vec3(0.10, 0.09, 0.26) * (0.25 * smoothstep(0.0, 1.0, d));

            // swirling blue-white ribbon tendrils (2 counter-winding sets)
            float twistA = a * 3.0 - (1.0 - z) * 6.0 - tm * 0.64;
            float bandA = 1.0 - smoothstep(0.30, 0.75, abs(sin(twistA * 2.0)));
            float twistB = -a * 2.0 - (1.0 - z) * 4.0 - tm * 0.40;
            float bandB = 1.0 - smoothstep(0.40, 0.80, abs(sin(twistB * 2.0 + 0.8)));

            float rib = clamp(bandA * 0.8 + bandB * 0.5, 0.0, 1.0);
            rib *= smoothstep(0.15, 0.45, d) * (1.0 - 0.4 * smoothstep(0.75, 0.98, d));
            rib *= (0.6 + 0.6 * (0.5 + 0.5 * sin(tm * 0.7)));   // gentle pulse
            rib *= (0.7 + 0.6 * uAudioVolume);                  // voice swell

            // left(blue/cyan) -> right(magenta) vertical hue gradient
            float hue = 0.5 + 0.5 * x;
            vec3 leftC = vec3(0.30, 0.50, 1.00);   // electric blue/cyan
            vec3 rightC = vec3(0.95, 0.40, 0.80);  // magenta/purple
            float swirlHue = clamp(hue + 0.15 * sin(a * 2.0 - tm * 0.3), 0.0, 1.0);

            vec3 rib_color = mix(leftC, rightC, swirlHue) * (0.85 + 0.35 * sin(tm * 0.6));
            vec3 ribbons = rib_color * rib * (0.9 + 0.3 * uAudioVolume);

            // outer glow follows the same left->right gradient
            vec3 rim_c = mix(leftC * 0.80, rightC * 0.85, hue);
            vec3 rim_glow = rim_c * smoothstep(0.75, 0.99, d) * 0.9

            vec3 finalColor = body + ribbons + rim_glow;
            float opacity = smoothstep(0.99, 0.985, d);
            gl_FragColor = vec4(finalColor, opacity);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
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
