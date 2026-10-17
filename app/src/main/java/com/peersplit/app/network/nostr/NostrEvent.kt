package com.peersplit.app.network.nostr

import com.peersplit.app.crypto.Bip340Util
import com.peersplit.app.crypto.HashUtil
import com.peersplit.app.crypto.KeyManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Nostr Event format according to NIP-01.
 * Used for decentralized, serverless message routing across peer devices.
 */
@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    companion object {
        const val KIND_PEERSPLIT_BLOCK = 1 // Persistent Nostr event kind, stored and replayed by all relays

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Calculates the Nostr event ID (SHA-256 of serialized array).
         * Format: [0, pubkey, created_at, kind, tags, content]
         */
        fun calculateEventId(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String
        ): String {
            val tagsJson = json.encodeToString(tags)
            val escapedContent = json.encodeToString(content)
            val serialized = "[0,\"$pubkey\",$createdAt,$kind,$tagsJson,$escapedContent]"
            return HashUtil.sha256Hex(serialized)
        }

        /**
         * Creates and signs a Nostr event for broadcasting.
         */
        fun createAndSign(
            pubkey: String,
            privateKeyHex: String,
            kind: Int = KIND_PEERSPLIT_BLOCK,
            tags: List<List<String>>,
            content: String,
            createdAt: Long = System.currentTimeMillis() / 1000
        ): NostrEvent {
            // Nostr pubkey and Schnorr signature derived according to BIP-340 / NIP-01
            val nostrPubkey = Bip340Util.getPublicKeyHex(privateKeyHex)
            val id = calculateEventId(nostrPubkey, createdAt, kind, tags, content)
            val sig = Bip340Util.signHex(id, privateKeyHex)

            return NostrEvent(
                id = id,
                pubkey = nostrPubkey,
                createdAt = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = sig
            )
        }
    }

    /**
     * Verifies the authenticity of this Nostr event according to BIP-340 / NIP-01.
     */
    fun verify(): Boolean {
        val expectedId = calculateEventId(pubkey, createdAt, kind, tags, content)
        if (!expectedId.equals(id, ignoreCase = true)) return false
        return Bip340Util.verifyHex(id, sig, pubkey)
    }
}
