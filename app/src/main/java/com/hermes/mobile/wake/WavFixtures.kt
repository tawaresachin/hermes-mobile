package com.hermes.mobile.wake

import java.io.InputStream

/** Minimal, strict PCM16 mono WAV reader for the bundled self-test fixtures. */
object WavFixtures {
    fun readPcm16Mono(input: InputStream): ShortArray = input.use { stream ->
        val riff = ByteArray(12)
        require(stream.readFully(riff) && riff.copyOfRange(0, 4).ascii() == "RIFF" && riff.copyOfRange(8, 12).ascii() == "WAVE") {
            "Not a RIFF/WAVE file"
        }
        var channels = -1
        var sampleRate = -1
        var bits = -1
        var format = -1
        var dataSize = -1
        val header = ByteArray(8)
        while (stream.readFully(header)) {
            val id = header.copyOfRange(0, 4).ascii()
            val size = le32(header, 4)
            if (id == "fmt ") {
                val fmt = ByteArray(size)
                require(stream.readFully(fmt) && fmt.size >= 16) { "Truncated fmt chunk" }
                format = le16(fmt, 0)
                channels = le16(fmt, 2)
                sampleRate = le32(fmt, 4)
                bits = le16(fmt, 14)
            } else if (id == "data") {
                dataSize = size
                break
            } else {
                var skipped = 0
                while (skipped < size) {
                    val n = stream.skip((size - skipped).toLong())
                    if (n <= 0) break
                    skipped += n.toInt()
                }
            }
        }
        require(format == 1 && channels == 1 && sampleRate == WakeWordDetector.SAMPLE_RATE && bits == 16) {
            "Fixture must be 16 kHz mono PCM16 (fmt=$format ch=$channels rate=$sampleRate bits=$bits)"
        }
        require(dataSize > 0 && dataSize % 2 == 0) { "Invalid data chunk size: $dataSize" }
        val bytes = ByteArray(dataSize)
        require(stream.readFully(bytes)) { "Truncated data chunk" }
        ShortArray(dataSize / 2) { i -> ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() }
    }

    private fun InputStream.readFully(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun ByteArray.ascii(): String = String(this, Charsets.US_ASCII)

    private fun le16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
