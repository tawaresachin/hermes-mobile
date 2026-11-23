package com.hermes.mobile.ui.screens.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TerminalKeyBytesTest {

    @Test
    fun controlKeysAreByteExact() {
        assertArrayEquals(byteArrayOf(0x03), terminalKeyBytes(TerminalKey.CtrlC))
        assertArrayEquals(byteArrayOf(0x1b), terminalKeyBytes(TerminalKey.Esc))
        assertArrayEquals(byteArrayOf(0x09), terminalKeyBytes(TerminalKey.Tab))
    }

    @Test
    fun historyKeysUseCursorSequences() {
        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x41),
            terminalKeyBytes(TerminalKey.Up)
        )
        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x42),
            terminalKeyBytes(TerminalKey.Down)
        )
    }
}
