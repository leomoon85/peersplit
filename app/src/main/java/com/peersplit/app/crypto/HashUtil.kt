package com.peersplit.app.crypto

import java.security.MessageDigest

/**
 * Utility functions for cryptographic hashing and hex representations.
 */
object HashUtil {

    private const val SHA256_ALGORITHM = "SHA-256"

    /**
     * Computes the SHA-256 hash of a byte array and returns the resulting 32 bytes.
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        return digest.digest(data)
    }

    /**
     * Computes the SHA-256 hash of a UTF-8 string and returns the resulting 32 bytes.
     */
    fun sha256(text: String): ByteArray {
        return sha256(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Computes the SHA-256 hash of a UTF-8 string and returns it as a lowercase hex string.
     */
    fun sha256Hex(text: String): String {
        return toHex(sha256(text))
    }

    /**
     * Computes the SHA-256 hash of a byte array and returns it as a lowercase hex string.
     */
    fun sha256Hex(data: ByteArray): String {
        return toHex(sha256(data))
    }

    /**
     * Converts a byte array to a lowercase hexadecimal string.
     */
    fun toHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = HEX_ARRAY[v ushr 4]
            hexChars[i * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Converts a hexadecimal string back to a byte array.
     */
    fun fromHex(hex: String): ByteArray {
        val cleanHex = hex.trim()
        val len = cleanHex.length
        require(len % 2 == 0) { "Hex string must have an even length" }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                    Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private val HEX_ARRAY = "0123456789abcdef".toCharArray()
}
