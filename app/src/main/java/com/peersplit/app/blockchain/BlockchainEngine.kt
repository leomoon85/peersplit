package com.peersplit.app.blockchain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Result of validating a blockchain ledger.
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val blockIndex: Long, val reason: String) : ValidationResult()
}

/**
 * Core consensus and micro-blockchain ledger engine.
 * Handles block creation, append validation, chain verification, and tamper detection.
 */
object BlockchainEngine {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Mints the Genesis block (Block 0) for a new group.
     */
    fun createGenesisBlock(
        groupName: String,
        currency: String,
        creatorPubkey: String,
        creatorDisplayName: String,
        privateKeyHex: String,
        groupId: String = "grp_${UUID.randomUUID().toString().take(12)}",
        creatorUpiId: String? = null,
        creatorPhone: String? = null
    ): Pair<String, Block> {
        val genesisPayload = GroupGenesisPayload(
            groupId = groupId,
            groupName = groupName,
            currency = currency,
            creatorPubkey = creatorPubkey,
            initialMembers = listOf(
                MemberInfo(
                    pubkey = creatorPubkey,
                    displayName = creatorDisplayName,
                    upiId = creatorUpiId,
                    phone = creatorPhone
                )
            )
        )
        val payloadJson = json.encodeToString(genesisPayload)

        val block = Block.createAndSign(
            index = 0,
            groupId = groupId,
            prevHash = Block.GENESIS_PREV_HASH,
            creatorPubkey = creatorPubkey,
            privateKeyHex = privateKeyHex,
            blockType = BlockType.GENESIS,
            payloadJson = payloadJson
        )

        return Pair(groupId, block)
    }

    /**
     * Mints a block for adding a new member to the group.
     */
    fun createMemberJoinBlock(
        groupId: String,
        prevBlock: Block,
        newMemberPubkey: String,
        newMemberName: String,
        authorPubkey: String,
        authorPrivateKeyHex: String,
        upiId: String? = null,
        claimedGhostPubkey: String? = null,
        phone: String? = null
    ): Block {
        val payload = MemberJoinedPayload(
            pubkey = newMemberPubkey,
            displayName = newMemberName,
            joinedAt = System.currentTimeMillis(),
            upiId = upiId,
            claimedGhostPubkey = claimedGhostPubkey,
            phone = phone
        )
        val payloadJson = json.encodeToString(payload)

        return Block.createAndSign(
            index = prevBlock.index + 1,
            groupId = groupId,
            prevHash = prevBlock.blockHash,
            creatorPubkey = authorPubkey,
            privateKeyHex = authorPrivateKeyHex,
            blockType = BlockType.MEMBER_JOIN,
            payloadJson = payloadJson
        )
    }

    /**
     * Mints an Expense block.
     */
    fun createExpenseBlock(
        groupId: String,
        prevBlock: Block,
        description: String,
        totalAmount: Double,
        currency: String,
        payerPubkey: String,
        splits: List<SplitItem>,
        category: String,
        note: String,
        authorPubkey: String,
        authorPrivateKeyHex: String,
        splitType: String = "EQUAL",
        payers: List<SplitItem> = emptyList(),
        expenseDateMillis: Long? = null,
        editedFromExpenseId: String? = null
    ): Block {
        val payload = ExpensePayload(
            expenseId = "exp_${UUID.randomUUID().toString().take(12)}",
            description = description.trim(),
            totalAmount = totalAmount,
            currency = currency,
            payerPubkey = payerPubkey,
            splits = splits,
            category = category,
            note = note,
            splitType = splitType,
            payers = payers,
            expenseDateMillis = expenseDateMillis,
            editedFromExpenseId = editedFromExpenseId
        )
        val payloadJson = json.encodeToString(payload)

        return Block.createAndSign(
            index = prevBlock.index + 1,
            groupId = groupId,
            prevHash = prevBlock.blockHash,
            creatorPubkey = authorPubkey,
            privateKeyHex = authorPrivateKeyHex,
            blockType = BlockType.EXPENSE,
            payloadJson = payloadJson
        )
    }

    /**
     * Mints an Expense Void block to immutably reverse a previously recorded expense.
     */
    fun createExpenseVoidBlock(
        groupId: String,
        prevBlock: Block,
        targetExpenseId: String,
        targetDescription: String,
        reason: String,
        authorPubkey: String,
        authorPrivateKeyHex: String
    ): Block {
        val payload = ExpenseVoidPayload(
            voidId = "void_${UUID.randomUUID().toString().take(12)}",
            targetExpenseId = targetExpenseId,
            targetDescription = targetDescription,
            reason = reason
        )
        val payloadJson = json.encodeToString(payload)

        return Block.createAndSign(
            index = prevBlock.index + 1,
            groupId = groupId,
            prevHash = prevBlock.blockHash,
            creatorPubkey = authorPubkey,
            privateKeyHex = authorPrivateKeyHex,
            blockType = BlockType.EXPENSE_VOID,
            payloadJson = payloadJson
        )
    }

    /**
     * Mints a Settlement block recording a debt payment between two members.
     */
    fun createSettlementBlock(
        groupId: String,
        prevBlock: Block,
        payerPubkey: String,
        payeePubkey: String,
        amount: Double,
        currency: String,
        note: String,
        authorPubkey: String,
        authorPrivateKeyHex: String
    ): Block {
        val payload = SettlementPayload(
            settlementId = "stl_${UUID.randomUUID().toString().take(12)}",
            payerPubkey = payerPubkey,
            payeePubkey = payeePubkey,
            amount = amount,
            currency = currency,
            note = note
        )
        val payloadJson = json.encodeToString(payload)

        return Block.createAndSign(
            index = prevBlock.index + 1,
            groupId = groupId,
            prevHash = prevBlock.blockHash,
            creatorPubkey = authorPubkey,
            privateKeyHex = authorPrivateKeyHex,
            blockType = BlockType.SETTLEMENT,
            payloadJson = payloadJson
        )
    }

    /**
     * Validates an entire chain of blocks from genesis up to the current tip.
     * Enforces:
     * 1. Genesis block has index 0 and GENESIS_PREV_HASH.
     * 2. Every block has mathematically valid SHA-256 hash.
     * 3. Every block has authentic Ed25519 digital signature from creator.
     * 4. Each block's prevHash precisely equals the preceding block's blockHash.
     * 5. Block indices are sequential (0, 1, 2, ...).
     */
    fun validateChain(chain: List<Block>): ValidationResult {
        if (chain.isEmpty()) {
            return ValidationResult.Invalid(-1, "Chain is empty")
        }

        // Validate Genesis
        val genesis = chain.first()
        if (genesis.index != 0L) {
            return ValidationResult.Invalid(0, "Genesis index must be 0, found ${genesis.index}")
        }
        if (genesis.prevHash != Block.GENESIS_PREV_HASH) {
            return ValidationResult.Invalid(0, "Invalid Genesis prevHash: ${genesis.prevHash}")
        }
        if (!genesis.verifyIntegrity()) {
            return ValidationResult.Invalid(0, "Genesis block signature or hash integrity failure")
        }

        // Validate subsequent blocks
        for (i in 1 until chain.size) {
            val prev = chain[i - 1]
            val curr = chain[i]

            if (curr.index != prev.index + 1) {
                return ValidationResult.Invalid(
                    curr.index,
                    "Non-sequential index: expected ${prev.index + 1}, found ${curr.index}"
                )
            }
            if (curr.prevHash != prev.blockHash) {
                return ValidationResult.Invalid(
                    curr.index,
                    "Broken hash pointer at index ${curr.index}: expected ${prev.blockHash}, found ${curr.prevHash}"
                )
            }
            if (!curr.hasValidHash()) {
                return ValidationResult.Invalid(curr.index, "Hash mismatch at index ${curr.index}")
            }
            if (!curr.hasValidSignature()) {
                return ValidationResult.Invalid(curr.index, "Signature verification failed at index ${curr.index}")
            }
        }

        return ValidationResult.Valid
    }

    /**
     * Fast O(N) structural chain validation for local database blocks whose Ed25519 signatures
     * were already verified prior to persistence. Checks sequential indices, hash pointers,
     * and Genesis anchor without repeating expensive elliptic-curve point multiplications on UI load.
     */
    fun verifyStructuralChain(chain: List<Block>): Boolean {
        if (chain.isEmpty()) return false
        val genesis = chain.first()
        if (genesis.index != 0L || genesis.prevHash != Block.GENESIS_PREV_HASH) return false

        for (i in 1 until chain.size) {
            val prev = chain[i - 1]
            val curr = chain[i]
            if (curr.index != prev.index + 1) return false
            if (curr.prevHash != prev.blockHash) return false
        }
        return true
    }

    /**
     * Validates an incoming block from a peer before appending it to the local chain tip.
     */
    fun validateNextBlock(newBlock: Block, currentTip: Block): Boolean {
        if (newBlock.index != currentTip.index + 1) return false
        if (newBlock.prevHash != currentTip.blockHash) return false
        if (newBlock.groupId != currentTip.groupId) return false
        return newBlock.verifyIntegrity()
    }
}
