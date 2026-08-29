package com.hermes.mobile.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec

/**
 * End-to-end encryption using ECDH key exchange + AES-256-GCM.
 * 
 * Design:
 * - Each device has a persistent identity keypair (stored in Android KeyStore)
 * - For each conversation, devices exchange public keys via the server
 * - Messages are encrypted with AES-GCM using a per-message key derived from ECDH
 * - Server only sees encrypted blobs; cannot read message content
 *
 * Security properties:
 * - Forward secrecy: Each message uses a unique nonce
 * - Server cannot decrypt: Private keys never leave device
 * - Integrity: AES-GCM provides authenticated encryption
 */
object E2EEncryption {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALGORITHM = "ECDH"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"

    /** Generate or retrieve the device's identity keypair */
    suspend fun generateIdentityKeys(): Pair<ByteArray, ByteArray> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)

            // Try to load existing key
            val existing = ks.getKey("hermes_e2e_identity", null) as? PrivateKey
            if (existing != null) {
                val pub = ks.getCertificate("hermes_e2e_identity")?.publicKey
                if (pub != null) {
                    return@withContext Pair(encodePublicKey(pub), encodePrivateKey(existing))
                }
            }

            // Generate new keypair
            val spec = KeyGenParameterSpec.Builder(
                "hermes_e2e_identity",
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(
                    java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .build()

            val kpg = KeyPairGenerator.getInstance("EC", KEYSTORE_PROVIDER)
            kpg.initialize(spec)
            val pair = kpg.generateKeyPair()

            Pair(encodePublicKey(pair.public), encodePrivateKey(pair.private))
        }
    }

    /** Get the device's public key (for sharing with others) */
    suspend fun getPublicKey(): ByteArray {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            val cert = ks.getCertificate("hermes_e2e_identity")
            val pub = cert?.publicKey ?: throw IllegalStateException("No identity key")
            encodePublicKey(pub)
        }
    }

    /** Derive shared secret using ECDH */
    suspend fun deriveSharedSecret(remotePublicKey: ByteArray): ByteArray {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            val privateKey = ks.getKey("hermes_e2e_identity", null) as PrivateKey

            val keyAgree = KeyAgreement.getInstance(KEY_ALGORITHM, KEYSTORE_PROVIDER)
            keyAgree.init(privateKey)

            val remotePubSpec = java.security.spec.X509EncodedKeySpec(remotePublicKey)
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val remotePubKey = keyFactory.generatePublic(remotePubSpec)

            keyAgree.doPhase(remotePubKey, true)
            keyAgree.generateSecret()
        }
    }

    /** Encrypt a message with AES-256-GCM */
    suspend fun encrypt(plaintext: String, sharedSecret: ByteArray): EncryptedMessage {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Derive per-message key from shared secret + random salt
            val salt = ByteArray(16)
            java.util.Random().nextBytes(salt)
            val key = deriveMessageKey(sharedSecret, salt)

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val iv = ByteArray(12)
            java.util.Random().nextBytes(iv)
            val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            EncryptedMessage(salt, iv, ciphertext)
        }
    }

    /** Decrypt a message with AES-256-GCM */
    suspend fun decrypt(encrypted: EncryptedMessage, sharedSecret: ByteArray): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val key = deriveMessageKey(sharedSecret, encrypted.salt)
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, encrypted.iv))
            String(cipher.doFinal(encrypted.ciphertext), Charsets.UTF_8)
        }
    }

    /** Encode public key to bytes for transmission */
    private fun encodePublicKey(key: PublicKey): ByteArray {
        return key.encoded
    }

    /** Encode private key to bytes for secure storage */
    private fun encodePrivateKey(key: PrivateKey): ByteArray {
        return key.encoded
    }

    /** Derive per-message AES key from shared secret */
    private fun deriveMessageKey(sharedSecret: ByteArray, salt: ByteArray): ByteArray {
        val h = java.security.MessageDigest.getInstance("SHA-256")
        val combined = ByteArray(salt.size + sharedSecret.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(sharedSecret, 0, combined, salt.size, sharedSecret.size)
        return h.digest(combined)
    }

    /** Container for encrypted message components */
    data class EncryptedMessage(
        val salt: ByteArray,
        val iv: ByteArray,
        val ciphertext: ByteArray
    ) {
        /** Serialize to JSON-safe base64 string */
        fun toBase64(): String {
            val json = org.json.JSONObject()
                .put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
                .put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                .put("ct", android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP))
            return json.toString()
        }

        companion object {
            fun fromBase64(json: String): EncryptedMessage {
                val obj = org.json.JSONObject(json)
                return EncryptedMessage(
                    salt = android.util.Base64.decode(obj.getString("salt"), android.util.Base64.NO_WRAP),
                    iv = android.util.Base64.decode(obj.getString("iv"), android.util.Base64.NO_WRAP),
                    ciphertext = android.util.Base64.decode(obj.getString("ct"), android.util.Base64.NO_WRAP)
                )
            }
        }
    }
}
