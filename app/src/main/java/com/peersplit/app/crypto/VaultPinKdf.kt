package com.peersplit.app.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64

object VaultPinKdf {

    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val PIN_KDF_SALT = "PeerSplit_Vault_Salt_v1:"

    private val secureRandom = SecureRandom()

    /**
     * Generates a modern PBKDF2-HMAC-SHA256 hash with 210,000 iterations and a random 16-byte salt.
     * Format: v2$<salt_b64>$<hash_b64>
     */
    fun hashPinV2(pin: String): String {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        val spec = PBEKeySpec(pin.trim().toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP)
        return "v2\$$saltB64\$$hashB64"
    }

    /**
     * Constant-time verification supporting:
     * 1. v2: PBKDF2-HMAC-SHA256 (210k iterations, random salt)
     * 2. v1: Salted SHA-256 x 4096 (PIN_KDF_SALT)
     * 3. legacy: Plain unsalted SHA-256
     */
    fun verifyPin(pin: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return true
        val cleanPin = pin.trim()

        if (storedHash.startsWith("v2$")) {
            val parts = storedHash.split('$')
            if (parts.size == 3) {
                return try {
                    val salt = Base64.decode(parts[1], Base64.NO_WRAP)
                    val expectedHash = Base64.decode(parts[2], Base64.NO_WRAP)
                    val spec = PBEKeySpec(cleanPin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
                    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    val computedHash = factory.generateSecret(spec).encoded
                    MessageDigest.isEqual(expectedHash, computedHash)
                } catch (e: Exception) {
                    false
                }
            }
        }

        // v1 Check: Salted SHA-256 x 4096 with constant-time compare
        try {
            var digest = HashUtil.sha256((PIN_KDF_SALT + cleanPin).toByteArray(Charsets.UTF_8))
            repeat(4096) {
                digest = HashUtil.sha256(digest)
            }
            val computedV1 = HashUtil.toHex(digest)
            if (MessageDigest.isEqual(computedV1.toByteArray(), storedHash.toByteArray())) {
                return true
            }
        } catch (e: Exception) {
            // Ignore
        }

        // Legacy Check: Plain unsalted SHA-256 with constant-time compare
        try {
            val legacySha256 = HashUtil.sha256Hex(cleanPin)
            if (MessageDigest.isEqual(legacySha256.toByteArray(), storedHash.toByteArray())) {
                return true
            }
        } catch (e: Exception) {
            // Ignore
        }

        return false
    }
}
