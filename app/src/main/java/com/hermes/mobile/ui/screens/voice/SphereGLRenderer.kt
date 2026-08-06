package com.hermes.mobile.ui.screens.voice

import com.hermes.mobile.DiagLog
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU voice-sphere renderer — thin wispy filament interior (noise
 * zero-crossings, curl-warped, dashed), state-driven neon palette,
 * Siri-style concentric rings, white-hot core.
 *   - State comes in as uniforms every rendered frame via
 *     [setVisualState]: uState/uColorA/uColorB/uHueShift/uRingCount/uPulseSpeed
 *   - uAudioVolume brightens filaments + core (capped, 0 at silence ->
 *     base intact) — consumed by the GLSL, no longer dead
 *   - ERROR state: red flash sin(t*10)^3 then a slow pulse
 *   - sim clock advanced by the frame driver (advanceTime) — rendering
 *     freezes exactly where it was when paused (privacy pause = zero work)
 *   - hardened: shader failure falls back to a solid dark disc (no crash)
 */
class SphereGLRenderer : GLSurfaceView.Renderer {

    companion object {
        // Must match SphereState ordinals in VoiceScreen.kt.
        const val STATE_IDLE = 0
        const val STATE_LISTENING = 1
        const val STATE_THINKING = 2
        const val STATE_SPEAKING = 3
        const val STATE_AWAITING = 4
        const val STATE_ERROR = 5
    }

    // ── Per-state palette (RGB 0..1, FRIDAY/Siri neon tokens) ──
    private val NEON_CYAN = floatArrayOf(0.412f, 0.898f, 0.898f)      // #69E5E5
    private val NEON_BLUE = floatArrayOf(0.239f, 0.545f, 1.0f)        // #3D8BFF
    private val NEON_VIOLET = floatArrayOf(0.541f, 0.231f, 1.0f)      // #8A3BFF
    private val NEON_MAGENTA = floatArrayOf(1.0f, 0.302f, 0.824f)     // #FF4DD2
    private val NEON_RED = floatArrayOf(1.0f, 0.302f, 0.302f)         // #FF4D4D
    private val CORE_WHITE = floatArrayOf(0.949f, 0.969f, 1.0f)       // #F2F7FF

    // Written from the UI/main thread, read on the GL thread (same pattern
    // as the original uAudioVolume). Volatile fields → no torn reads.
    @Volatile private var uAudioVolume = 0f
    @Volatile private var uProgress = 0f
    @Volatile private var uState = STATE_IDLE
    @Volatile private var uHueShift = 0f
    @Volatile private var colorA = NEON_CYAN
    @Volatile private var colorB = NEON_BLUE
    @Volatile private var ringCount = 2f
    @Volatile private var pulseSpeed = 0.5f
    @Volatile private var simTime = 0f

    private var program = 0
    private var uTimeLoc = -1
    private var uVolumeLoc = -1
    private var uStateLoc = -1
    private var uColorALoc = -1
    private var uColorBLoc = -1
    private var uHueShiftLoc = -1
    private var uRingCountLoc = -1
    private var uPulseSpeedLoc = -1
    private var aPosLoc = -1
    private var quadBuffer: FloatBuffer? = null

    fun setAudioVolume(volume: Float) {
        uAudioVolume = volume.coerceIn(0f, 1f)
    }

    /** Advance the renderer's simulation clock (called by the frame driver). */
    fun advanceTime(dt: Float) {
        if (dt > 0f) simTime += dt
    }

    /**
     * Upload the full visual state. Called by the frame driver each rendered
     * frame; palette + ring motion are derived from the state here and
     * consumed by the GLSL uniforms. [progress] drives ring speed during
     * SPEAKING (0..1); [hueShift] is an extra palette offset (THINKING adds
     * its own t*0.15 sweep inside onDrawFrame).
     */
    fun setVisualState(state: Int, volume: Float, progress: Float, hueShift: Float) {
        uState = state
        uAudioVolume = volume.coerceIn(0f, 1f)
        uProgress = progress.coerceIn(0f, 1f)
        uHueShift = hueShift
        when (state) {
            STATE_LISTENING -> { // cyan rings, brightness ← mic amplitude
                colorA = NEON_CYAN
                colorB = NEON_BLUE
                ringCount = 4f
                pulseSpeed = 1.3f
            }
            STATE_THINKING -> { // violet → magenta hue sweep
                colorA = NEON_VIOLET
                colorB = NEON_MAGENTA
                ringCount = 3f
                pulseSpeed = 0.8f
            }
            STATE_SPEAKING -> { // blue + white-hot core
                colorA = NEON_BLUE
                colorB = CORE_WHITE
                ringCount = 3f
                pulseSpeed = 1.0f
            }
            STATE_ERROR -> { // red flash
                colorA = NEON_RED
                colorB = NEON_RED
                ringCount = 5f
                pulseSpeed = 3.2f
            }
            else -> { // IDLE / AWAITING: cyan dim, slow pulse
                colorA = NEON_CYAN
                colorB = NEON_BLUE
                ringCount = 2f
                pulseSpeed = 0.5f
            }
        }
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
        uniform float uState;      // 0 idle, 1 listening, 2 thinking, 3 speaking, 4 awaiting, 5 error
        uniform vec3 uColorA;      // state palette (left/outer)
        uniform vec3 uColorB;      // state palette (right/inner)
        uniform float uHueShift;   // palette sweep (THINKING: t*0.15)
        uniform float uRingCount;  // Siri ring layer density
        uniform float uPulseSpeed; // Siri ring travel speed

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
            float state = uState;
            float vol = clamp(uAudioVolume, 0.0, 1.0);

            // thin wispy filaments: noise zero-crossings, curl-warped, dashed
            float warp = snoise(vec3(x * 1.4, y * 1.4, tm * 0.10));
            float qx = x + 1.6 * sin(a + warp * 3.0) * (1.0 - d);
            float qy = y - 1.6 * cos(a + warp * 3.0) * (1.0 - d);
            float fz = snoise(vec3(qx * 1.6, qy * 1.6, tm * 0.08));
            float filament = 1.0 - smoothstep(0.0, 0.065, abs(fz));

            // sparse + dashed + radial placement + gentle pulse
            float solv = 0.5 + 0.5 * sin(warp * 6.0);
            filament *= smoothstep(0.26, 0.52, solv);
            float dash = 0.5 + 0.5 * sin(snoise(vec3(x * 2.2, y * 2.2, tm * 0.12)) * 5.0 + tm);
            filament *= smoothstep(0.15, 0.42, dash);
            filament *= smoothstep(0.12, 0.32, d) * (1.0 - smoothstep(0.78, 0.96, d));
            filament *= (0.75 + 0.25 * sin(tm * 0.6));
            filament = clamp(filament, 0.0, 1.0);

            // uniform-driven palette (replaces hardcoded leftC/rightC)
            float hue = clamp(0.5 + 0.5 * x + uHueShift, 0.0, 1.0);
            vec3 palette = mix(uColorA, uColorB, hue);

            // dark blue body + radial haze
            vec3 body = vec3(0.02, 0.02, 0.05)
                      + vec3(0.12, 0.16, 0.30) * (0.34 * smoothstep(0.18, 0.85, d));

            // luminous rim
            vec3 rim_glow = palette * smoothstep(0.68, 0.99, d) * 2.2;

            // Siri-style concentric rings: cheap additive arcs sweeping
            // outward (fract(d * uRingCount - tm * uPulseSpeed))
            float ringPhase = fract(d * uRingCount - tm * uPulseSpeed);
            float ring = pow(ringPhase, 14.0);
            ring *= 0.6 + 0.4 * sin(tm * 1.8 - d * 6.0);
            ring *= smoothstep(0.06, 0.22, d) * (1.0 - smoothstep(0.90, 0.99, d));
            vec3 ringColor = mix(uColorB, vec3(1.0), 0.3) * ring * (1.1 + vol * 2.4);

            // white-hot core (vol pushes it hotter)
            float coreMask = exp(-d * 5.0);
            vec3 core = mix(palette, vec3(1.0), 0.9) * coreMask * (1.0 + vol * 1.8);

            // state-driven motion
            float statePulse = 1.0;
            float errFlash = 0.0;
            if (state < 1.5) {            // IDLE: cyan dim, slow pulse
                statePulse = 0.45 + 0.35 * (0.5 + 0.5 * sin(tm * 0.8));
            } else if (state < 2.5) {     // LISTENING: rings, brightness ← volume
                statePulse = 0.75 + 0.55 * vol;
            } else if (state < 3.5) {     // THINKING: hue sweep via uHueShift
                statePulse = 0.85 + 0.15 * sin(tm * 2.4);
            } else if (state < 4.5) {     // SPEAKING: bright blue + white-hot core
                statePulse = 0.9 + 0.35 * vol;
            } else if (state < 5.5) {     // AWAITING: same as IDLE
                statePulse = 0.45 + 0.35 * (0.5 + 0.5 * sin(tm * 0.8));
            } else {                      // ERROR: red flash sin(t*10)^3, then slow pulse
                float s = sin(tm * 10.0);
                errFlash = max(s * s * s, 0.0);
                statePulse = 0.45 + 0.3 * (0.5 + 0.5 * sin(tm * 1.2));
            }

            vec3 finalColor = (body + filaments * (0.55 + 0.9 * vol)) * statePulse
                            + rim_glow * statePulse
                            + ringColor * statePulse
                            + core * statePulse
                            + vec3(1.0, 0.12, 0.08) * errFlash * 2.0;
            float opacity = 1.0 - smoothstep(0.985, 0.99, d);
            gl_FragColor = vec4(finalColor, opacity);
        }
    """.trimIndent()

    // ── Fallback: the ORIGINAL v2.22 fragment shader (known to work on
    // the user's device). Used only if the state-driven shader above fails
    // to compile on the device GPU. Ignores the state uniforms (uState/
    // uColorA/uColorB/... are -1 and uploads are no-ops) — still animates
    // via uTime with the classic blue→magenta plasma.
    private val FALLBACK_FRAGMENT_SHADER = """
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

            // thin wispy filaments: noise zero-crossings, curl-warped, dashed
            float warp = snoise(vec3(x * 1.4, y * 1.4, tm * 0.10));
            float qx = x + 1.6 * sin(a + warp * 3.0) * (1.0 - d);
            float qy = y - 1.6 * cos(a + warp * 3.0) * (1.0 - d);
            float fz = snoise(vec3(qx * 1.6, qy * 1.6, tm * 0.08));
            float filament = 1.0 - smoothstep(0.0, 0.065, abs(fz));

            // sparse + dashed + radial placement + gentle pulse
            float solv = 0.5 + 0.5 * sin(warp * 6.0);
            filament *= smoothstep(0.26, 0.52, solv);
            float dash = 0.5 + 0.5 * sin(snoise(vec3(x * 2.2, y * 2.2, tm * 0.12)) * 5.0 + tm);
            filament *= smoothstep(0.15, 0.42, dash);
            filament *= smoothstep(0.12, 0.32, d) * (1.0 - smoothstep(0.78, 0.96, d));
            filament *= (0.75 + 0.25 * sin(tm * 0.6));
            filament = clamp(filament, 0.0, 1.0);

            // blue-left -> magenta-right hue
            float hue = 0.5 + 0.5 * x;
            vec3 leftC = vec3(0.16, 0.32, 1.00);
            vec3 rightC = vec3(0.45, 0.14, 0.95);
            float flu = clamp(hue + 0.1 * sin(-tm * 0.2), 0.0, 1.0);
            vec3 filaments = mix(leftC, rightC, flu) * filament * 2.6;

            // dark blue body + radial haze
            vec3 body = vec3(0.02, 0.02, 0.05)
                      + vec3(0.12, 0.16, 0.30) * (0.34 * smoothstep(0.18, 0.85, d));

            // luminous rim + soft blue core
            vec3 rim_c = mix(leftC, rightC, hue);
            vec3 rim_glow = rim_c * smoothstep(0.68, 0.99, d) * 2.2;
            vec3 central = vec3(0.03, 0.04, 0.08) * clamp(1.0 - 2.4 * d, 0.0, 1.0) * 1.3;

            vec3 finalColor = body + filaments + rim_glow + central;
            float opacity = 1.0 - smoothstep(0.985, 0.99, d);
            gl_FragColor = vec4(finalColor, opacity);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Two-stage shader strategy: the state-driven shader first; if the
        // device GPU rejects it (some ES2 drivers are picky), fall back to
        // the ORIGINAL v2.22 shader which is known to work on this device.
        // Only if BOTH fail do we clear transparent (Compose disc shows).
        program = try {
            createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        } catch (t1: Throwable) {
            android.util.Log.e("SphereGL", "state shader failed, trying fallback", t1)
            DiagLog.e("GL", "state shader failed: ${t1.message}")
            try {
                createProgram(VERTEX_SHADER, FALLBACK_FRAGMENT_SHADER)
            } catch (t2: Throwable) {
                android.util.Log.e("SphereGL", "fallback shader failed too", t2)
                DiagLog.e("GL", "fallback shader failed too: ${t2.message}")
                0
            }
        }
        DiagLog.i("GL", "program=$program (0=both shaders failed, disc fallback active)")
        uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
        uVolumeLoc = GLES20.glGetUniformLocation(program, "uAudioVolume")
        uStateLoc = GLES20.glGetUniformLocation(program, "uState")
        uColorALoc = GLES20.glGetUniformLocation(program, "uColorA")
        uColorBLoc = GLES20.glGetUniformLocation(program, "uColorB")
        uHueShiftLoc = GLES20.glGetUniformLocation(program, "uHueShift")
        uRingCountLoc = GLES20.glGetUniformLocation(program, "uRingCount")
        uPulseSpeedLoc = GLES20.glGetUniformLocation(program, "uPulseSpeed")
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        simTime = 0f
        GLES20.glClearColor(0f, 0f, 0f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0) {
            // Shader failed to compile — clear TRANSPARENT so the Compose
            // neon disc behind the GL view shows through (never a black hole).
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        GLES20.glUseProgram(program)

        val t = simTime
        GLES20.glUniform1f(uTimeLoc, t)
        GLES20.glUniform1f(uVolumeLoc, uAudioVolume)
        GLES20.glUniform1f(uStateLoc, uState.toFloat())
        GLES20.glUniform3f(uColorALoc, colorA[0], colorA[1], colorA[2])
        GLES20.glUniform3f(uColorBLoc, colorB[0], colorB[1], colorB[2])
        // THINKING: violet → magenta hue sweep (bounded so the sweep cycles
        // forever instead of clamping to magenta after a few seconds).
        val hueShift = uHueShift + if (uState == STATE_THINKING) (t * 0.15f) % 1f else 0f
        GLES20.glUniform1f(uHueShiftLoc, hueShift)
        var speed = pulseSpeed
        if (uState == STATE_SPEAKING) speed += uProgress * 1.4f // rings accelerate with speech
        GLES20.glUniform1f(uRingCountLoc, ringCount)
        GLES20.glUniform1f(uPulseSpeedLoc, speed)

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
        // aPos location cached in onSurfaceCreated — the driver call is
        // pointless per frame (locations are fixed at link time).
        val aPos = aPosLoc
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
