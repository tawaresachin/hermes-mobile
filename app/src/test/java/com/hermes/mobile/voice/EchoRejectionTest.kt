package com.hermes.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class EchoRejectionTest {

    @Test
    fun `pure echo is rejected to blank`() {
        val tts = "The quick brown fox jumps over the lazy dog"
        assertEquals("", EchoRejection.stripEchoPrefix(tts, tts))
    }

    @Test
    fun `echo prefix with user tail salvages the tail`() {
        val tts = "The quick brown fox jumps over the lazy dog"
        val heard = "the quick brown fox jumps over the lazy dog and then it sleeps"
        assertEquals("and then it sleeps", EchoRejection.stripEchoPrefix(heard, tts))
    }

    @Test
    fun `tiny salvaged tail is rejected`() {
        val tts = "hello world"
        val heard = "hello world ok"
        assertEquals("", EchoRejection.stripEchoPrefix(heard, tts))
    }

    @Test
    fun `unrelated speech is returned unchanged`() {
        val tts = "The quick brown fox jumps over the lazy dog"
        val heard = "please remind me to buy milk"
        assertEquals(heard, EchoRejection.stripEchoPrefix(heard, tts))
    }

    @Test
    fun `partial fuzzy match below overlap threshold is unchanged`() {
        val tts = "The quick brown fox jumps over the lazy dog"
        val heard = "the quick brown fox jumped away"
        // "jumped" vs "jumps" is below the 0.8 word-similarity bar and the
        // 4/9 word overlap is below 0.7 → the whole transcript is kept.
        assertEquals(heard, EchoRejection.stripEchoPrefix(heard, tts))
    }

    @Test
    fun `blank inputs pass through`() {
        assertEquals("", EchoRejection.stripEchoPrefix("", "anything"))
        assertEquals("some text", EchoRejection.stripEchoPrefix("some text", ""))
    }
}
