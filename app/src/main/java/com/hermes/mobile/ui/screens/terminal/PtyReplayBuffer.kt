package com.hermes.mobile.ui.screens.terminal

import java.io.ByteArrayOutputStream

/**
 * Bounded byte history for a live gateway PTY.
 *
 * The shell survives rotation / tab switches (one PTY per process), but the
 * WebView that paints it does not: a new screen starts with an empty xterm.
 * Replaying the recent tail hides that gap and keeps the session usable.
 *
 * Whole chunks are evicted from the head until the cap is met, so the
 * snapshot is always an exact suffix of what the shell emitted (it may start
 * mid-escape-sequence when the cap is hit; xterm recovers on the next SGR).
 */
internal class PtyReplayBuffer(private val capacityBytes: Int = 256 * 1024) {

    private val chunks = ArrayDeque<ByteArray>()
    private var size = 0

    @Synchronized
    fun add(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        chunks.addLast(chunk)
        size += chunk.size
        while (size > capacityBytes && chunks.size > 1) {
            size -= chunks.removeFirst().size
        }
    }

    @Synchronized
    fun snapshot(): ByteArray {
        if (size == 0) return ByteArray(0)
        val out = ByteArrayOutputStream(size)
        for (c in chunks) out.write(c)
        return out.toByteArray()
    }

    @Synchronized
    fun clear() {
        chunks.clear()
        size = 0
    }

    @Synchronized
    fun sizeBytes(): Int = size
}
