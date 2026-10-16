package com.peersplit.app.blockchain

import com.peersplit.app.crypto.HashUtil
import com.peersplit.app.crypto.KeyManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Immutable block in a group's micro-blockchain.
 */
@Serializable
data class Block(
    val index: Long,
    val groupId: String,
    val prevHash: String,
    val timestamp: Long,
    val creatorPubkey: String,
    val blockType: BlockType,
    val payloadJson: String,
    val blockHash: String,
    val signature: String
) {
    companion object {
        const val GENESIS_PREV_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Computes the deterministic SHA-256 hash for a block's fields.
         */
        fun computeHash(
            index: Long,
            groupId: String,
            prevHash: String,
            timestamp: Long,
            creatorPubkey: String,
            blockType: BlockType,
            payloadJson: String
        ): String {
            val content = "$index:$groupId:$prevHash:$timestamp:$creatorPubkey:${blockType.name}:$payloadJson"
            return HashUtil.sha256Hex(content)
        }

        /**
         * Factory function to create and sign a new Block.
         */
        fun createAndSign(
            index: Long,
            groupId: String,
            prevHash: String,
            timestamp: Long = System.currentTimeMillis(),
            creatorPubkey: String,
            privateKeyHex: String,
            blockType: BlockType,
            payloadJson: String
        ): Block {
            val hash = computeHash(index, groupId, prevHash, timestamp, creatorPubkey, blockType, payloadJson)
            val hashBytes = HashUtil.fromHex(hash)
            val signature = KeyManager.signHex(hashBytes, privateKeyHex)

            return Block(
                index = index,
                groupId = groupId,
                prevHash = prevHash,
                timestamp = timestamp,
                creatorPubkey = creatorPubkey,
                blockType = blockType,
                payloadJson = payloadJson,
                blockHash = hash,
                signature = signature
            )
        }
    }

    /**
     * Verifies that the block's hash is mathematically correct.
     */
    fun hasValidHash(): Boolean {
        val calculated = computeHash(index, groupId, prevHash, timestamp, creatorPubkey, blockType, payloadJson)
        return calculated.equals(blockHash, ignoreCase = true)
    }

    /**
     * Verifies that the creator's digital signature is authentic over the block hash.
     */
    fun hasValidSignature(): Boolean {
        return try {
            val hashBytes = HashUtil.fromHex(blockHash)
            KeyManager.verifyHex(hashBytes, signature, creatorPubkey)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Full cryptographic integrity verification.
     */
    fun verifyIntegrity(): Boolean {
        return hasValidHash() && hasValidSignature()
    }
}
