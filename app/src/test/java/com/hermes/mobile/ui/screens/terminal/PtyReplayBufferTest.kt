package com.hermes.mobile.ui.screens.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PtyReplayBufferTest {

    @Test
    fun keepsBoundedTailInOrder() {
        val buf = PtyReplayBuffer(capacityBytes = 8)
        buf.add("aaaa".toByteArray())
        buf.add("bbbb".toByteArray())
        assertArrayEquals("aaaabbbb".toByteArray(), buf.snapshot())

        buf.add("cccc".toByteArray())
        assertArrayEquals("bbbbcccc".toByteArray(), buf.snapshot())
        assertEquals(8, buf.sizeBytes())
    }

    @Test
    fun rejectsEmptyAndClears() {
        val buf = PtyReplayBuffer(capacityBytes = 8)
        buf.add(ByteArray(0))
        assertEquals(0, buf.sizeBytes())
        buf.add("x".toByteArray())
        buf.clear()
        assertArrayEquals(ByteArray(0), buf.snapshot())
    }
}
