package com.peersplit.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages device-level cryptographic identity (Ed25519 public/private keys),
 * hardware-backed Android Keystore encryption at rest, digital signing of ledger blocks,
 * and signature verification.
 */
object KeyManager {

    private const val PREFS_NAME = "peersplit_crypto_identity"
    private const val KEY_PRIV_ENC = "priv_key_enc_v2"
    private const val KEY_PRIV_HEX_LEGACY = "priv_key_hex"
    private const val KEY_PUB_HEX = "pub_key_hex"
    private const val KEY_USER_NAME = "user_display_name"
    private const val KEY_UPI_ID = "user_upi_id"
    private const val KEY_PHONE = "user_phone_number"
    private const val KEYSTORE_ALIAS = "peersplit_master_identity_key"
    private const val PIN_KDF_SALT = "PeerSplit_Vault_Salt_v1:"

    private val secureRandom = SecureRandom()

    private val _identityVersion = MutableStateFlow(0L)
    val identityVersion: StateFlow<Long> = _identityVersion.asStateFlow()

    @Volatile
    private var cachedIdentity: UserIdentity? = null
    @Volatile
    private var cachedPrivateKeyHex: String? = null

    fun notifyIdentityChanged() {
        cachedIdentity = null
        cachedPrivateKeyHex = null
        _identityVersion.value = System.currentTimeMillis()
    }

    /**
     * Normalizes a phone number to its canonical 10-digit suffix (e.g. "+91 98765-43210" -> "9876543210")
     * so matching works seamlessly regardless of country code or formatting.
     */
    fun normalizePhone(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val digits = raw.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }

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
        val upiId: String? = null,
        val phone: String? = null
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
     * Computes a salted, stretched hash for Secret Vault PINs (backwards-compatible check available).
     */
    fun hashSecretPin(pin: String): String {
        var digest = HashUtil.sha256((PIN_KDF_SALT + pin.trim()).toByteArray(Charsets.UTF_8))
        repeat(4096) {
            digest = HashUtil.sha256(digest)
        }
        return HashUtil.toHex(digest)
    }

    /**
     * Verifies a Secret Vault PIN against a stored hash (supports both v2 salted stretched hash and legacy SHA-256).
     */
    fun verifySecretPin(pin: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return true
        val saltedHash = hashSecretPin(pin)
        if (saltedHash == storedHash) return true
        // Legacy fallback for previously created groups
        return HashUtil.sha256Hex(pin.trim()) == storedHash
    }

    /**
     * Gets or creates the local device user identity.
     */
    @Synchronized
    fun getOrCreateIdentity(context: Context): UserIdentity {
        cachedIdentity?.let { return it }

        val prefs = getPrefs(context)
        val existingPubHex = prefs.getString(KEY_PUB_HEX, null)
        val existingName = prefs.getString(KEY_USER_NAME, "My Phone") ?: "My Phone"
        val existingUpi = prefs.getString(KEY_UPI_ID, null)
        val existingPhone = prefs.getString(KEY_PHONE, null)

        if (existingPubHex != null) {
            // Migrate legacy plaintext private key if present and Keystore succeeds
            val legacyPriv = prefs.getString(KEY_PRIV_HEX_LEGACY, null)
            if (legacyPriv != null) {
                val migrated = storePrivateKeyEncrypted(prefs, legacyPriv)
                if (migrated) {
                    prefs.edit().remove(KEY_PRIV_HEX_LEGACY).apply()
                }
            }
            val identity = UserIdentity(
                publicKeyHex = existingPubHex,
                displayName = existingName,
                upiId = existingUpi,
                phone = existingPhone
            )
            cachedIdentity = identity
            return identity
        }

        // Generate brand new identity
        val keyPair = generateKeyPair()
        storePrivateKeyEncrypted(prefs, keyPair.privateKeyHex)
        prefs.edit()
            .putString(KEY_PUB_HEX, keyPair.publicKeyHex)
            .putString(KEY_USER_NAME, "My Phone")
            .apply()

        cachedPrivateKeyHex = keyPair.privateKeyHex
        val identity = UserIdentity(publicKeyHex = keyPair.publicKeyHex, displayName = "My Phone")
        cachedIdentity = identity
        return identity
    }

    /**
     * Retrieves the stored private key in hex (decrypted via Android Keystore).
     */
    @Synchronized
    fun getPrivateKeyHex(context: Context): String {
        cachedPrivateKeyHex?.let { return it }

        val prefs = getPrefs(context)
        val encPriv = prefs.getString(KEY_PRIV_ENC, null)
        if (encPriv != null) {
            val decrypted = decryptWithKeystore(encPriv)
            if (decrypted != null) {
                cachedPrivateKeyHex = decrypted
                return decrypted
            }
        }

        // Check legacy plaintext key and migrate if Keystore succeeds
        val legacyPriv = prefs.getString(KEY_PRIV_HEX_LEGACY, null)
        if (legacyPriv != null) {
            val migrated = storePrivateKeyEncrypted(prefs, legacyPriv)
            if (migrated) {
                prefs.edit().remove(KEY_PRIV_HEX_LEGACY).apply()
            }
            cachedPrivateKeyHex = legacyPriv
            return legacyPriv
        }

        // Otherwise initialize identity
        getOrCreateIdentity(context)
        val newEnc = prefs.getString(KEY_PRIV_ENC, null)
        val resolved = newEnc?.let { decryptWithKeystore(it) }
            ?: prefs.getString(KEY_PRIV_HEX_LEGACY, null)!!
        cachedPrivateKeyHex = resolved
        return resolved
    }

    /**
     * Updates the user's local display name.
     */
    fun updateDisplayName(context: Context, newName: String) {
        getPrefs(context).edit().putString(KEY_USER_NAME, newName.trim()).apply()
        notifyIdentityChanged()
    }

    /**
     * Updates the user's local UPI ID for settlements.
     */
    fun updateUpiId(context: Context, newUpiId: String) {
        getPrefs(context).edit().putString(KEY_UPI_ID, newUpiId.trim()).apply()
        notifyIdentityChanged()
    }

    fun getUpiId(context: Context): String {
        return getPrefs(context).getString(KEY_UPI_ID, "") ?: ""
    }

    /**
     * Updates the user's local phone number (optional, used for member matching & UPI).
     */
    fun updatePhone(context: Context, newPhone: String) {
        val clean = normalizePhone(newPhone)
        getPrefs(context).edit().putString(KEY_PHONE, clean).apply()
        notifyIdentityChanged()
    }

    fun getPhone(context: Context): String {
        return getPrefs(context).getString(KEY_PHONE, "") ?: ""
    }

    /**
     * Restores cryptographic identity from backup.
     */
    @Synchronized
    fun restoreIdentity(
        context: Context,
        privHex: String,
        pubHex: String,
        displayName: String,
        upiId: String? = null,
        phone: String? = null
    ) {
        val prefs = getPrefs(context)
        val migrated = storePrivateKeyEncrypted(prefs, privHex)
        val editor = prefs.edit()
            .putString(KEY_PUB_HEX, pubHex)
            .putString(KEY_USER_NAME, displayName)
            .putString(KEY_UPI_ID, upiId)
            .putString(KEY_PHONE, normalizePhone(phone))
        if (migrated) {
            editor.remove(KEY_PRIV_HEX_LEGACY)
        }
        editor.apply()
        cachedIdentity = null
        cachedPrivateKeyHex = privHex
        notifyIdentityChanged()
    }

    private fun storePrivateKeyEncrypted(prefs: SharedPreferences, privHex: String): Boolean {
        val encrypted = encryptWithKeystore(privHex)
        if (encrypted != null) {
            val decrypted = decryptWithKeystore(encrypted)
            if (decrypted == privHex) {
                prefs.edit().putString(KEY_PRIV_ENC, encrypted).apply()
                return true
            }
        }
        prefs.edit().putString(KEY_PRIV_HEX_LEGACY, privHex).apply()
        return false
    }

    private fun getOrCreateKeystoreSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existingKey = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existingKey != null) {
                return existingKey.secretKey
            }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            null
        }
    }

    private fun encryptWithKeystore(plaintext: String): String? {
        return try {
            val secretKey = getOrCreateKeystoreSecretKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + ciphertext.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptWithKeystore(encoded: String): String? {
        return try {
            val secretKey = getOrCreateKeystoreSecretKey() ?: return null
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val ivSize = combined[0].toInt() and 0xFF
            val iv = combined.copyOfRange(1, 1 + ivSize)
            val ciphertext = combined.copyOfRange(1 + ivSize, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
