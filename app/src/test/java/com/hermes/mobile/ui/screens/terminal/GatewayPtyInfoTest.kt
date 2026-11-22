package com.hermes.mobile.ui.screens.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayPtyInfoTest {

    @Test
    fun formatsLiveValuesWithoutHardcodingThem() {
        val info = GatewayPtyInfo("alice", "box-7", "/home/alice/.hermes", "/home/alice/.hermes")
        assertEquals("alice@box-7 · /home/alice/.hermes", gatewayPtySubtitle(info))
    }

    @Test
    fun usesHermesHomeWhenCwdIsBlank() {
        val info = GatewayPtyInfo("alice", "box-7", "/hermes/home", "")
        assertEquals("alice@box-7 · /hermes/home", gatewayPtySubtitle(info))
    }

    @Test
    fun nullOrIncompleteInfoIsHonestlyUnavailable() {
        assertEquals(PTY_INFO_UNAVAILABLE, gatewayPtySubtitle(null))
        assertEquals(PTY_INFO_UNAVAILABLE, gatewayPtySubtitle(GatewayPtyInfo("", "", "", "")))
        // A path with no identity at all is still not a detected machine.
        assertEquals(
            PTY_INFO_UNAVAILABLE,
            gatewayPtySubtitle(GatewayPtyInfo("", "", "", "/hermes/home"))
        )
    }
}
