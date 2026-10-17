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

        private val verifiedHashesCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val verifiedSignaturesCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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

            val block = Block(
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
            verifiedHashesCache.add(block.hashCacheKey())
            verifiedSignaturesCache.add(block.sigCacheKey())
            return block
        }
    }

    private fun hashCacheKey(): String =
        "$index:$groupId:$prevHash:$timestamp:$creatorPubkey:${blockType.name}:$payloadJson|$blockHash"

    private fun sigCacheKey(): String =
        "$blockHash:$creatorPubkey:$signature"

    /**
     * Verifies that the block's hash is mathematically correct.
     */
    fun hasValidHash(): Boolean {
        val key = hashCacheKey()
        if (verifiedHashesCache.contains(key)) return true
        val calculated = computeHash(index, groupId, prevHash, timestamp, creatorPubkey, blockType, payloadJson)
        val valid = calculated.equals(blockHash, ignoreCase = true)
        if (valid) verifiedHashesCache.add(key)
        return valid
    }

    /**
     * Verifies that the creator's digital signature is authentic over the block hash.
     */
    fun hasValidSignature(): Boolean {
        val key = sigCacheKey()
        if (verifiedSignaturesCache.contains(key)) return true
        return try {
            val hashBytes = HashUtil.fromHex(blockHash)
            val valid = KeyManager.verifyHex(hashBytes, signature, creatorPubkey)
            if (valid) verifiedSignaturesCache.add(key)
            valid
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
