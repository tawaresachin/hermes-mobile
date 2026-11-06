package com.hermes.mobile.ui.screens.terminal

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * ANSI/VT100 subset renderer for the terminal output buffer.
 *
 * Scope (documented limitation, not a silent one):
 *  - SGR color/attribute sequences (16, 256 and 24-bit color,
 *    bold/underline/inverse) → AnnotatedString styles
 *  - \r\n / \r / \f normalization, C0 control-char stripping
 *  - OSC (ESC ] … BEL|ST), DCS/SSI (ESC P|ESC X … ST) consumed:
 *    xterm titles and vim/less DCS must not leak into the text
 *  - cursor addressing + erase-line/screen (ESC [H/K/L/J): consumed,
 *    NOT emulated — full VT screen state is the gateway-side PTY
 *    bridge's job, out of scope for device-side SSH
 *
 * Performance guards:
 *  - the caller checks [needsParsing] first; plain output costs one
 *    indexOf scan and zero parsing
 *  - span budget: a hostile color-spam stream cannot produce unbounded
 *    AnnotatedString spans — beyond [MAX_SPANS] the rest renders plain
 */
object TerminalAnsi {

    private const val MAX_SPANS = 8_000

    /** True when [raw] contains escape/control material worth parsing. */
    fun needsParsing(raw: String): Boolean =
        raw.indexOf('\u001B') >= 0 || raw.indexOf('\b') >= 0 || raw.indexOf('\u000C') >= 0

    /** Render [raw] as styled text: SGR colors + C0 cleanup, no VT
     *  screen emulation. Safe for arbitrary input (no escape can
     *  throw or leak control text). */
    fun render(raw: String): AnnotatedString =
        buildAnnotatedString {
            var i = 0
            val n = raw.length

            // Current SGR state. -1 = unset. Colors are UNSIGNED ARGB
            // (alpha 0xFF) held in Longs so the -1 sentinel never
            // collides with a real color (Int ARGB values are negative).
            var fg: Long = -1L
            var bg: Long = -1L
            var bold = false
            var underline = false
            var inverse = false
            var spans = 0
            val run = StringBuilder(64)

            fun flush() {
                if (run.isEmpty()) return
                val text = run.toString()
                run.setLength(0)
                // Inverse video: swap fg/bg (the renderer has no
                // inverse flag; the swap is the visual equivalent for
                // the common solid-color cases).
                val effFg = if (inverse) bg else fg
                val effBg = if (inverse) fg else bg
                val styled = effFg >= 0 || effBg >= 0 || bold || underline
                if (styled && spans < MAX_SPANS) {
                    spans++
                    withStyle(
                        SpanStyle(
                            color = if (effFg >= 0) argbColor(effFg.toInt()) else Color.Unspecified,
                            background = if (effBg >= 0) argbColor(effBg.toInt()) else Color.Unspecified,
                            textDecoration =
                                if (underline) TextDecoration.Underline else TextDecoration.None
                        )
                    ) { append(text) }
                } else {
                    append(text)
                }
            }

            fun applySgr(params: List<Int>) {
                var k = 0
                while (k < params.size) {
                    when (val p = params[k]) {
                        0 -> { bold = false; underline = false; inverse = false }
                        1 -> bold = true
                        22 -> bold = false
                        4 -> underline = true
                        24 -> underline = false
                        7 -> inverse = true
                        27 -> inverse = false
                        39 -> fg = -1
                        49 -> bg = -1
                        in 30..37 -> fg = sgr16(p - 30)
                        in 40..47 -> bg = sgr16(p - 40)
                        in 90..97 -> fg = sgr16(p - 90, bright = true)
                        in 100..107 -> bg = sgr16(p - 100, bright = true)
                        38, 48 -> {
                            val targetFg = p == 38
                            val mode = params.getOrNull(k + 1)
                            when (mode) {
                                5 -> {
                                    val v = params.getOrNull(k + 2) ?: 0
                                    val c = sgr256(v)
                                    if (targetFg) fg = c else bg = c
                                    k += 2
                                }
                                2 -> {
                                    val r = (params.getOrNull(k + 2) ?: 0).coerceIn(0, 255)
                                    val g = (params.getOrNull(k + 3) ?: 0).coerceIn(0, 255)
                                    val b = (params.getOrNull(k + 4) ?: 0).coerceIn(0, 255)
                                    val c = 0xFF000000L or
                                        (r.toLong() shl 16) or
                                        (g.toLong() shl 8) or
                                        b.toLong()
                                    if (targetFg) fg = c else bg = c
                                    k += 4
                                }
                            }
                        }
                    }
                    k++
                }
            }

            while (i < n) {
                val c = raw[i]
                if (c != '\u001B') {
                    when {
                        c == '\r' -> { i++; if (i < n && raw[i] == '\n') i++; run.append('\n') }
                        c == '\n' || c == '\u000C' -> { i++; run.append('\n') }
                        c == '\t' -> { i++; run.append(' ') }
                        c < ' ' -> i++          // other C0: strip
                        else -> { i++; run.append(c) }
                    }
                    continue
                }

                // ── escape: flush any pending run (style boundary) ──
                flush()
                i++
                if (i >= n) break
                when (val e = raw[i]) {
                    '[' -> {
                        // CSI: numeric params (';' ':' separated) then
                        // final byte 0x40-0x7E. Only 'm' (SGR) acts on
                        // the display; every other CSI is consumed and
                        // ignored (cursor ops are documented as not
                        // emulated — the bytes must not reach the text).
                        i++
                        val params = ArrayList<Int>(3)
                        var cur = 0
                        var sawDigit = false
                        var finalByte = 0.toChar()
                        var malformed = false
                        while (i < n) {
                            val b = raw[i]
                            when {
                                b in '0'..'9' -> {
                                    // First digit initializes cur; the
                                    // 6553 clamp bounds hostile numbers.
                                    cur = when {
                                        sawDigit && cur < 6553 -> cur * 10 + (b - '0')
                                        sawDigit -> 6553
                                        else -> b - '0'
                                    }
                                    sawDigit = true
                                    i++
                                }
                                b == ';' || b == ':' -> {
                                    params.add(if (sawDigit) cur else 0)
                                    cur = 0; sawDigit = false; i++
                                    if (params.size > 32) { i = n; malformed = true }
                                }
                                b in '@'..'~' -> {
                                    finalByte = b
                                    params.add(if (sawDigit) cur else 0)
                                    i++
                                    break
                                }
                                else -> { i++; malformed = true }
                            }
                        }
                        if (!malformed && finalByte == 'm') applySgr(params)
                    }
                    ']' -> i = consumeOsc(raw, i + 1, n)
                    'P', 'X' -> i = consumeToSt(raw, i + 1, n)
                    '(' -> i = if (i + 2 < n) i + 2 else n
                    '=' , 'M' -> i++
                    else -> {
                        // Lone ESC followed by a non-dispatch byte:
                        // drop ONLY the escape, keep the byte (it is
                        // ordinary text, e.g. a truncated keypress).
                    }
                }
            }
            flush()
        }

    /** ARGB int → Color. Compose's Color(Long) is RGBA-float packed,
     *  NOT ARGB — decompose through the component constructor. */
    private fun argbColor(argb: Int): Color =
        Color((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF, 0xFF)

    /** Consume OSC starting AFTER ']'. Returns index PAST the
     *  terminator (BEL, ESC \, or end-of-input). */
    private fun consumeOsc(raw: String, from: Int, n: Int): Int {
        var p = from
        while (p < n) {
            when (raw[p]) {
                '\u0007' -> return p + 1
                '\u001B' -> {
                    if (p + 1 < n && raw[p + 1] == '\\') return p + 2
                    return p
                }
            }
            p++
        }
        return n
    }

    /** Consume DCS/SSI starting AFTER the P/X. Returns index PAST the
     *  ST terminator (ESC \\) or end-of-input. */
    private fun consumeToSt(raw: String, from: Int, n: Int): Int {
        var p = from
        while (p < n) {
            if (raw[p] == '\u001B' && p + 1 < n && raw[p + 1] == '\\') return p + 2
            p++
        }
        return n
    }

    /** 16-color SGR index (0-15) → ARGB as unsigned Long.
     *  [bright] = the 8-15 row. Palette matches xterm: 0-7 standard
     *  VGA, 8-15 the 0xFF55-style bright row (no extra boost — the
     *  bright row IS the bright color). */
    private fun sgr16(index: Int, bright: Boolean = false): Long {
        val reds = intArrayOf(0x00, 0x80, 0x00, 0x80, 0x00, 0x80, 0x00, 0xC0,
            0x80, 0xFF, 0x55, 0xFF, 0x55, 0xFF, 0x55, 0xFF)
        val greens = intArrayOf(0x00, 0x00, 0x80, 0x80, 0x00, 0x00, 0x80, 0xC0,
            0x80, 0x55, 0xFF, 0xFF, 0x55, 0x55, 0xFF, 0xFF)
        val blues = intArrayOf(0x00, 0x00, 0x00, 0x80, 0x80, 0x80, 0x80, 0xC0,
            0x80, 0x55, 0x55, 0x55, 0xFF, 0xFF, 0xFF, 0xFF)
        val vi = ((index and 7) + if (index >= 8 || bright) 8 else 0).coerceIn(0, 15)
        return 0xFF000000L or
            (reds[vi].toLong() shl 16) or
            (greens[vi].toLong() shl 8) or
            blues[vi].toLong()
    }

    /** 256-color (cube + gray ramp) → ARGB as unsigned Long, 0-15 via [sgr16]. */
    private fun sgr256(v: Int): Long {
        val vv = v.coerceIn(0, 255)
        if (vv < 16) return sgr16(vv, bright = vv >= 8)
        return when {
            vv < 232 -> {
                val i = vv - 16
                val ramp = intArrayOf(0, 95, 135, 175, 215, 255)
                0xFF000000L or
                    (ramp[i / 36].toLong() shl 16) or
                    (ramp[(i / 6) % 6].toLong() shl 8) or
                    ramp[i % 6].toLong()
            }
            else -> {
                val gray = ((vv - 232).coerceIn(0, 23) * 10 + 8).toLong()
                0xFF000000L or (gray shl 16) or (gray shl 8) or gray
            }
        }
    }
}

/**
 * Device-aware PTY geometry. The remote shell's line width should
 * approximate the visible column width, or long output wraps early.
 * Pure estimate — the PTY is allocated at connect time, before the
 * output surface exists, so there is nothing to measure live:
 *  - cols: usable width / ~9dp monospace char estimate
 *  - rows: remaining height after form/key rows, 24..60
 */
fun ptyGeometry(context: Context): Pair<Int, Int> {
    val dm = context.resources.displayMetrics
    val wDp = dm.widthPixels / dm.density
    val hDp = dm.heightPixels / dm.density
    val cols = (wDp / 9f).toInt().coerceIn(40, 160)
    val rows = ((hDp - 320f) / 18f).toInt().coerceIn(24, 60)
    return cols to rows
}

/**
 * Same estimate as [ptyGeometry] but driven by an actual viewport
 * (pixels), for the gateway bridge's LIVE resize on rotation /
 * fold: the plugin re-sends TIOCSWINSZ, no reconnect needed.
 */
fun ptyGeometry(context: Context, wPx: Int, hPx: Int): Pair<Int, Int> {
    val dm = context.resources.displayMetrics
    val wDp = wPx / dm.density
    val hDp = hPx / dm.density
    val cols = (wDp / 9f).toInt().coerceIn(40, 160)
    val rows = ((hDp - 320f) / 18f).toInt().coerceIn(24, 60)
    return cols to rows
}
