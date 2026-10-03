package com.example.arruler.processing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pure framing of an AES/GCM message for storage in a string preference:
 * base64( version(1 byte) | ivLength(1 byte) | iv | ciphertext+tag ). JVM-testable; no Keystore here.
 */
object TokenFraming {
    const val VERSION: Int = 1
    const val MAX_IV = 32

    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size in 1..MAX_IV) { "iv length ${iv.size} out of range" }
        require(ciphertext.isNotEmpty()) { "empty ciphertext" }
        val out = ByteArray(2 + iv.size + ciphertext.size)
        out[0] = VERSION.toByte()
        out[1] = iv.size.toByte()
        iv.copyInto(out, 2)
        ciphertext.copyInto(out, 2 + iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** (iv, ciphertext), or null when [text] is not a frame this version wrote. */
    fun decode(text: String): Pair<ByteArray, ByteArray>? {
        val raw = try { Base64.decode(text, Base64.NO_WRAP) } catch (e: IllegalArgumentException) { return null }
        return split(raw)
    }

    /** The byte-level half of [decode] (no Android Base64), used by the unit tests directly. */
    fun split(raw: ByteArray): Pair<ByteArray, ByteArray>? {
        if (raw.size < 3) return null
        if ((raw[0].toInt() and 0xFF) != VERSION) return null
        val ivLen = raw[1].toInt() and 0xFF
        if (ivLen !in 1..MAX_IV || raw.size <= 2 + ivLen) return null
        return raw.copyOfRange(2, 2 + ivLen) to raw.copyOfRange(2 + ivLen, raw.size)
    }

    /** The byte-level half of [encode]. */
    fun join(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size in 1..MAX_IV && ciphertext.isNotEmpty())
        val out = ByteArray(2 + iv.size + ciphertext.size)
        out[0] = VERSION.toByte(); out[1] = iv.size.toByte()
        iv.copyInto(out, 2); ciphertext.copyInto(out, 2 + iv.size)
        return out
    }
}

/** Encrypts the pairing token; a fake implements it in tests. */
interface TokenCipher {
    /** Framed base64 text ([TokenFraming]); throws when the secure store is unusable. */
    fun encrypt(plain: String): String

    /** The token, or null when the frame is malformed or its key is gone (wiped lock screen, restored backup). */
    fun decrypt(framed: String): String?
}

/**
 * AES-256/GCM with a non-exportable Android Keystore key (alias [alias]). The key is created on first use,
 * randomised encryption (the Keystore picks a fresh IV per message), no user authentication required so
 * the app can reach the PC without unlocking again.
 */
class KeystoreTokenCipher(private val alias: String = ALIAS) : TokenCipher {

    override fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        return TokenFraming.encode(c.iv, c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(framed: String): String? {
        val (iv, ct) = TokenFraming.decode(framed) ?: return null
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        const val ALIAS = "armeasure_pc_token"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
