package com.peersplit.app.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Explicitly authenticated Ed25519 key-distribution envelope for forward/post-removal secrecy.
 * Carries an AES epoch key encrypted confidentially for a specific recipient, bound to a
 * transition operation (e.g. MemberRemoved), group, and epoch, and cryptographically signed
 * by the author using their Ed25519 identity key.
 */
@Serializable
data class KeyDistributionEnvelope(
    val groupId: String,
    val epoch: Int,
    val authorPublicKey: String,
    val recipientPublicKey: String,
    val transitionOperationId: String,
    val keyCiphertext: String,
    val signature: String
) {
    /**
     * Canonical string representation for Ed25519 digital signature generation and verification.
     */
    fun canonicalPayload(): ByteArray {
        val raw = "$groupId:$epoch:${authorPublicKey.trim().lowercase()}:${recipientPublicKey.trim().lowercase()}:$transitionOperationId:$keyCiphertext"
        return raw.toByteArray(Charsets.UTF_8)
    }

    /**
     * Verifies the cryptographic signature against the author's Ed25519 public key.
     */
    fun verifySignature(): Boolean {
        return try {
            val payload = canonicalPayload()
            KeyManager.verifyHex(payload, signature, authorPublicKey.trim().lowercase())
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun createAndSign(
            groupId: String,
            epoch: Int,
            authorPublicKey: String,
            authorPrivateKeyHex: String,
            recipientPublicKey: String,
            transitionOperationId: String,
            keyCiphertext: String
        ): KeyDistributionEnvelope {
            val normAuthor = authorPublicKey.trim().lowercase()
            val normRecipient = recipientPublicKey.trim().lowercase()
            val raw = "$groupId:$epoch:$normAuthor:$normRecipient:$transitionOperationId:$keyCiphertext"
            val sig = KeyManager.signHex(raw.toByteArray(Charsets.UTF_8), authorPrivateKeyHex)
            return KeyDistributionEnvelope(
                groupId = groupId,
                epoch = epoch,
                authorPublicKey = normAuthor,
                recipientPublicKey = normRecipient,
                transitionOperationId = transitionOperationId,
                keyCiphertext = keyCiphertext,
                signature = sig
            )
        }

        fun fromJson(jsonStr: String): KeyDistributionEnvelope? {
            return try {
                json.decodeFromString(serializer(), jsonStr)
            } catch (_: Exception) {
                null
            }
        }
    }
}
