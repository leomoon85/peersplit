package com.peersplit.app.crypto

import android.content.Context
import android.content.SharedPreferences
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Manages device-level cryptographic identity (Ed25519 public/private keys),
 * digital signing of ledger blocks, and signature verification.
 */
object KeyManager {

    private const val PREFS_NAME = "peersplit_crypto_identity"
    private const val KEY_PRIV_HEX = "priv_key_hex"
    private const val KEY_PUB_HEX = "pub_key_hex"
    private const val KEY_USER_NAME = "user_display_name"
    private const val KEY_UPI_ID = "user_upi_id"

    private val secureRandom = SecureRandom()

    data class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    ) {
        val privateKeyHex: String get() = HashUtil.toHex(privateKey)
        val publicKeyHex: String get() = HashUtil.toHex(publicKey)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as KeyPair
            return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int {
            var result = privateKey.contentHashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }

    data class UserIdentity(
        val publicKeyHex: String,
        val displayName: String,
        val upiId: String? = null
    ) {
        val shortPubkey: String
            get() = if (publicKeyHex.length > 12) {
                "${publicKeyHex.take(6)}...${publicKeyHex.takeLast(6)}"
            } else publicKeyHex
    }

    /**
     * Generates a new Ed25519 keypair.
     */
    fun generateKeyPair(): KeyPair {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()

        val priv = (keyPair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (keyPair.public as Ed25519PublicKeyParameters).encoded

        return KeyPair(privateKey = priv, publicKey = pub)
    }

    /**
     * Generates a KeyPair deterministically from a 32-byte seed.
     */
    fun fromSeed(seed: ByteArray): KeyPair {
        val privParams = Ed25519PrivateKeyParameters(seed, 0)
        val pubParams = privParams.generatePublicKey()
        return KeyPair(
            privateKey = privParams.encoded,
            publicKey = pubParams.encoded
        )
    }

    /**
     * Signs a message using the user's Ed25519 private key.
     */
    fun sign(message: ByteArray, privateKeyBytes: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        val privParams = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
        signer.init(true, privParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /**
     * Signs a message and returns the signature as a hex string.
     */
    fun signHex(message: ByteArray, privateKeyHex: String): String {
        val privBytes = HashUtil.fromHex(privateKeyHex)
        val sigBytes = sign(message, privBytes)
        return HashUtil.toHex(sigBytes)
    }

    /**
     * Verifies an Ed25519 digital signature against a message and public key.
     */
    fun verify(message: ByteArray, signatureBytes: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Signer()
            val pubParams = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            verifier.init(false, pubParams)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifies a hex-encoded signature against a message and hex public key.
     */
    fun verifyHex(message: ByteArray, signatureHex: String, publicKeyHex: String): Boolean {
        return try {
            val sigBytes = HashUtil.fromHex(signatureHex)
            val pubBytes = HashUtil.fromHex(publicKeyHex)
            verify(message, sigBytes, pubBytes)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets or creates the local device user identity.
     */
    fun getOrCreateIdentity(context: Context): UserIdentity {
        val prefs = getPrefs(context)
        val existingPubHex = prefs.getString(KEY_PUB_HEX, null)
        val existingName = prefs.getString(KEY_USER_NAME, "My Phone") ?: "My Phone"
        val existingUpi = prefs.getString(KEY_UPI_ID, null)

        if (existingPubHex != null) {
            return UserIdentity(publicKeyHex = existingPubHex, displayName = existingName, upiId = existingUpi)
        }

        // Generate brand new identity
        val keyPair = generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIV_HEX, keyPair.privateKeyHex)
            .putString(KEY_PUB_HEX, keyPair.publicKeyHex)
            .putString(KEY_USER_NAME, "My Phone")
            .apply()

        return UserIdentity(publicKeyHex = keyPair.publicKeyHex, displayName = "My Phone")
    }

    /**
     * Retrieves the stored private key in hex.
     */
    fun getPrivateKeyHex(context: Context): String {
        val prefs = getPrefs(context)
        var privHex = prefs.getString(KEY_PRIV_HEX, null)
        if (privHex == null) {
            getOrCreateIdentity(context)
            privHex = prefs.getString(KEY_PRIV_HEX, null)!!
        }
        return privHex
    }

    /**
     * Updates the user's local display name.
     */
    fun updateDisplayName(context: Context, newName: String) {
        getPrefs(context).edit().putString(KEY_USER_NAME, newName.trim()).apply()
    }

    /**
     * Updates the user's local UPI ID for settlements.
     */
    fun updateUpiId(context: Context, newUpiId: String) {
        getPrefs(context).edit().putString(KEY_UPI_ID, newUpiId.trim()).apply()
    }

    fun getUpiId(context: Context): String {
        return getPrefs(context).getString(KEY_UPI_ID, "") ?: ""
    }

    /**
     * Restores cryptographic identity from backup.
     */
    fun restoreIdentity(context: Context, privHex: String, pubHex: String, displayName: String, upiId: String? = null) {
        getPrefs(context).edit()
            .putString(KEY_PRIV_HEX, privHex)
            .putString(KEY_PUB_HEX, pubHex)
            .putString(KEY_USER_NAME, displayName)
            .putString(KEY_UPI_ID, upiId)
            .apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
