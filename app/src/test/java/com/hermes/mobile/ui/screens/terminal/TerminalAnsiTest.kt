package com.hermes.mobile.ui.screens.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAnsiTest {

    /** Compose Color here has no toArgb(): round components back to ARGB. */
    private fun argbOf(c: Color): Int =
        0xFF000000.toInt() or
            (((c.red * 255f + 0.5f).toInt() and 0xFF) shl 16) or
            (((c.green * 255f + 0.5f).toInt() and 0xFF) shl 8) or
            ((c.blue * 255f + 0.5f).toInt() and 0xFF)


    @Test
    fun plainTextPassesThrough() {
        assertFalse(TerminalAnsi.needsParsing("hello world\n"))
        val out = AnnotatedString("hello world\n")
        assertEquals("hello world\n", out.text)
    }

    @Test
    fun crlfFoldsToNewline() {
        val out = TerminalAnsi.render("a\r\nb")
        assertEquals("a\nb", out.text)
    }

    @Test
    fun bareCrFoldsToNewline() {
        val out = TerminalAnsi.render("a\rb")
        assertEquals("a\nb", out.text)
    }

    @Test
    fun c0Stripped() {
        val out = TerminalAnsi.render("a\u0007b\u001Bc") // BEL, lone ESC
        assertEquals("abc", out.text)
    }

    @Test
    fun tabBecomesSpace() {
        val out = TerminalAnsi.render("a\tb")
        assertEquals("a b", out.text)
    }

    @Test
    fun sgrForegroundRed() {
        val out = TerminalAnsi.render("\u001B[31mRED\u001B[0m")
        assertEquals("RED", out.text)
        val red = out.spanStyles.firstOrNull { it.item.color != Color.Unspecified }
        assertTrue("expected a colored span, got ${out.spanStyles}", red != null)
        assertEquals(0xFF800000.toInt(), red!!.item.color.let { argbOf(it) })
    }

    @Test
    fun sgrBackgroundGreen() {
        val out = TerminalAnsi.render("\u001B[42mX\u001B[0m")
        val span = out.spanStyles.firstOrNull { it.item.background != Color.Unspecified }
        assertTrue(span != null)
        assertEquals(0xFF008000.toInt(), span!!.item.background.let { argbOf(it) })
    }

    @Test
    fun brightForeground() {
        val out = TerminalAnsi.render("\u001B[91mY\u001B[0m")
        val span = out.spanStyles.firstOrNull { it.item.color != Color.Unspecified }
        // bright red = xterm 0xFFFF5555
        assertEquals(0xFFFF5555.toInt(), span!!.item.color.let { argbOf(it) })
    }

    @Test
    fun color256() {
        val out = TerminalAnsi.render("\u001B[38;5;208mZ\u001B[0m")
        val span = out.spanStyles.firstOrNull { it.item.color != Color.Unspecified }
        assertTrue(span != null)
    }

    @Test
    fun trueColor() {
        val out = TerminalAnsi.render("\u001B[38;2;10;20;30mQ\u001B[0m")
        val span = out.spanStyles.firstOrNull { it.item.color != Color.Unspecified }
        assertEquals(0xFF0A141E.toInt(), span!!.item.color.let { argbOf(it) })
    }

    @Test
    fun inverseSwapsColors() {
        val out = TerminalAnsi.render("\u001B[7;31;47mINV\u001B[0m")
        val span = out.spanStyles.firstOrNull { it.item.color != Color.Unspecified }
        // fg=red(1)=0xFF800000, bg=white(7)=0xFFC0C0C0 → inverted:
        // color=bg, background=fg
        assertEquals(0xFFC0C0C0.toInt(), span!!.item.color.let { argbOf(it) })
        assertEquals(0xFF800000.toInt(), span.item.background.let { argbOf(it) })
    }

    @Test
    fun underlineFlag() {
        val out = TerminalAnsi.render("\u001B[4mU\u001B[0m")
        val span = out.spanStyles.firstOrNull {
            it.item.textDecoration == TextDecoration.Underline
        }
        assertTrue(span != null)
    }

    @Test
    fun oscTitleConsumed() {
        // xterm-style title: ESC ] 0 ; my title BEL
        val out = TerminalAnsi.render("\u001B]0;my title\u0007after")
        assertEquals("after", out.text)
    }

    @Test
    fun oscStTerminator() {
        val out = TerminalAnsi.render("\u001B]2;w\u001B\\after")
        assertEquals("after", out.text)
    }

    @Test
    fun dcsConsumedToSt() {
        // DCS (like vim's mode-setting): ESC P ... ESC \
        val out = TerminalAnsi.render("\u001BP\u0047\u005B0q\u001B\\after")
        assertEquals("after", out.text)
    }

    @Test
    fun unknownCsiConsumed() {
        // cursor to 5,10: ESC [ 5 ; 10 H — consumed, not emulated,
        // and must NOT leak "5;10" into the text.
        val out = TerminalAnsi.render("\u001B[5;10Hx")
        assertEquals("x", out.text)
    }

    @Test
    fun eraseCsiConsumed() {
        val out = TerminalAnsi.render("\u001B[2J\u001B[Ky")
        assertEquals("y", out.text)
    }

    @Test
    fun charsetDesignatorConsumed() {
        val out = TerminalAnsi.render("\u001B(Bz")
        assertEquals("z", out.text)
    }

    @Test
    fun emptyParamsMeanDefault() {
        // ESC[m == ESC[0m: after the reset nothing is styled.
        val out = TerminalAnsi.render("\u001B[1mB\u001B[m")
        // exactly one styled span (the bold run), the reset produced none
        assertEquals(1, out.spanStyles.size)
    }

    @Test
    fun hostileColorSpamBounded() {
        // 20k alternating colors: span count must stay capped, no crash.
        val sb = StringBuilder()
        repeat(20_000) { i ->
            sb.append("\u001B[38;5;").append(i % 256).append("m")
            sb.append('x')
        }
        val out = TerminalAnsi.render(sb.toString())
        assertEquals(20_000, out.length)
        // MAX_SPANS = 8000 → never more styled spans than that
        assertTrue(
            "spanStyles=${out.spanStyles.size}",
            out.spanStyles.size <= 8_000
        )
    }

    @Test
    fun malformedTruncatedCsiDropped() {
        // ESC [ 3 (no final byte, input ends mid-CSI): the trailing
        // params must not leak.
        val out = TerminalAnsi.render("\u001B[3")
        assertEquals("", out.text)
    }

    @Test
    fun runLengthOneSpanPerSegment() {
        val out = TerminalAnsi.render("\u001B[31maaaa\u001B[32mbbbb")
        // two colored runs → two spans
        assertEquals(2, out.spanStyles.size)
    }
}
