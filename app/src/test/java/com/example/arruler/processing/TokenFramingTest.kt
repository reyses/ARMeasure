package com.example.arruler.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The pure byte framing of the Keystore-encrypted token (the Keystore itself is device-only). */
class TokenFramingTest {
    private val iv = ByteArray(12) { (it + 1).toByte() }
    private val ct = ByteArray(40) { (it * 3).toByte() }

    @Test fun joinSplitRoundTrip() {
        val raw = TokenFraming.join(iv, ct)
        assertEquals(2 + 12 + 40, raw.size)
        assertEquals(TokenFraming.VERSION, raw[0].toInt())
        assertEquals(12, raw[1].toInt())
        val (i, c) = TokenFraming.split(raw)!!
        assertArrayEquals(iv, i)
        assertArrayEquals(ct, c)
    }

    @Test fun rejectsUnknownVersionTruncationAndBadIvLength() {
        val raw = TokenFraming.join(iv, ct)
        assertNull(TokenFraming.split(raw.copyOf().also { it[0] = 2 }))
        assertNull(TokenFraming.split(raw.copyOf().also { it[1] = 0 }))
        assertNull(TokenFraming.split(raw.copyOf().also { it[1] = 100 }))
        assertNull(TokenFraming.split(raw.copyOf(2 + 12)))          // iv but no ciphertext
        assertNull(TokenFraming.split(raw.copyOf(2)))
        assertNull(TokenFraming.split(ByteArray(0)))
    }

    @Test fun joinValidatesInput() {
        try { TokenFraming.join(ByteArray(0), ct); fail() } catch (e: IllegalArgumentException) { }
        try { TokenFraming.join(iv, ByteArray(0)); fail() } catch (e: IllegalArgumentException) { }
        try { TokenFraming.join(ByteArray(40), ct); fail() } catch (e: IllegalArgumentException) { }
    }
}
