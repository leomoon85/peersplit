package com.peersplit.app.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Encryption (E2EE) utilities using AES-256-GCM.
 * Used to encrypt block payloads before broadcasting over public relays.
 */
object EncryptionUtil {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256
    private const val IV_SIZE_BYTES = 12 // 96-bit IV recommended for GCM
    private const val TAG_SIZE_BITS = 128

    private val secureRandom = SecureRandom()

    /**
     * Generates a new cryptographically secure 256-bit AES secret key.
     */
    fun generateGroupKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(KEY_SIZE_BITS, secureRandom)
        return keyGen.generateKey().encoded
    }

    /**
     * Encrypts plaintext using AES-256-GCM with a random IV.
     * The returned ByteArray has format: [12-byte IV][Ciphertext + 16-byte Auth Tag]
     */
    fun encrypt(plaintext: ByteArray, keyBytes: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE_BYTES)
        secureRandom.nextBytes(iv)

        val secretKey: SecretKey = SecretKeySpec(keyBytes, ALGORITHM)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val cipherBytes = cipher.doFinal(plaintext)

        // Prepend IV to ciphertext
        val output = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, output, 0, iv.size)
        System.arraycopy(cipherBytes, 0, output, iv.size, cipherBytes.size)
        return output
    }

    /**
     * Encrypts a UTF-8 string and returns a Base64-encoded string.
     */
    fun encryptString(plainText: String, keyBytes: ByteArray): String {
        val encrypted = encrypt(plainText.toByteArray(Charsets.UTF_8), keyBytes)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /**
     * Decrypts AES-256-GCM data formatted as [12-byte IV][Ciphertext + Auth Tag].
     */
    fun decrypt(encryptedData: ByteArray, keyBytes: ByteArray): ByteArray {
        require(encryptedData.size > IV_SIZE_BYTES) { "Data too short for AES-GCM IV" }

        val iv = ByteArray(IV_SIZE_BYTES)
        System.arraycopy(encryptedData, 0, iv, 0, IV_SIZE_BYTES)

        val cipherSize = encryptedData.size - IV_SIZE_BYTES
        val cipherText = ByteArray(cipherSize)
        System.arraycopy(encryptedData, IV_SIZE_BYTES, cipherText, 0, cipherSize)

        val secretKey: SecretKey = SecretKeySpec(keyBytes, ALGORITHM)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return cipher.doFinal(cipherText)
    }

    /**
     * Decrypts a Base64-encoded string and returns the UTF-8 plaintext string.
     */
    fun decryptString(base64Ciphertext: String, keyBytes: ByteArray): String {
        val decoded = Base64.decode(base64Ciphertext, Base64.NO_WRAP)
        val decrypted = decrypt(decoded, keyBytes)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * Formats a byte array as a URL-safe Base64 string for group invites / QR codes.
     */
    fun keyToBase64(keyBytes: ByteArray): String {
        return Base64.encodeToString(keyBytes, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /**
     * Parses a group key from a Base64 string.
     */
    fun keyFromBase64(base64Key: String): ByteArray {
        return Base64.decode(base64Key, Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
