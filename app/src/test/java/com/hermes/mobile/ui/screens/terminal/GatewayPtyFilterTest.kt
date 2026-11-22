package com.hermes.mobile.ui.screens.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GatewayPtyFilterTest {

    @Test
    fun stripsPromptSpMarker() {
        val raw = "before".toByteArray() + GatewayPromptFilter.MARKER + "after".toByteArray()
        val out = GatewayPromptFilter().feed(raw)
        assertArrayEquals("beforeafter".toByteArray(), out)
    }

    @Test
    fun stripsPromptSpMarkerSplitAcrossFrames() {
        val filter = GatewayPromptFilter()
        val split = GatewayPromptFilter.MARKER.size / 2
        assertArrayEquals(
            ByteArray(0),
            filter.feed(GatewayPromptFilter.MARKER.copyOfRange(0, split))
        )
        assertArrayEquals(
            ByteArray(0),
            filter.feed(GatewayPromptFilter.MARKER.copyOfRange(split, GatewayPromptFilter.MARKER.size))
        )
        assertArrayEquals("prompt".toByteArray(), filter.feed("prompt".toByteArray()))
    }

    @Test
    fun keepsPartialLookalikeBytes() {
        val lookalike = byteArrayOf(0x1B, '['.code.toByte(), 'X'.code.toByte())
        assertArrayEquals(lookalike, GatewayPromptFilter().feed(lookalike))
    }

    @Test
    fun keepsMismatchingByteAfterPartialMarker() {
        val prefix = byteArrayOf(0x1B, '['.code.toByte(), '1'.code.toByte())
        val mismatch = 'X'.code.toByte()
        val raw = prefix + byteArrayOf(mismatch) + GatewayPromptFilter.MARKER + "after".toByteArray()
        val want = prefix + byteArrayOf(mismatch) + "after".toByteArray()
        assertArrayEquals(want, GatewayPromptFilter().feed(raw))
    }
}
