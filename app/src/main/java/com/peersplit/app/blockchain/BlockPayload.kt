package com.peersplit.app.blockchain

import kotlinx.serialization.Serializable

/**
 * Supported types of blocks in a group's micro-ledger.
 */
enum class BlockType {
    GENESIS,
    MEMBER_JOIN,
    EXPENSE,
    SETTLEMENT,
    EXPENSE_VOID
}

/**
 * Data payload for an individual split or payer contribution in an expense.
 */
@Serializable
data class SplitItem(
    val userPubkey: String,
    val amount: Double
)

/**
 * Payload when a group is first created (Genesis block).
 */
@Serializable
data class GroupGenesisPayload(
    val groupId: String,
    val groupName: String,
    val currency: String,
    val creatorPubkey: String,
    val initialMembers: List<MemberInfo>
)

@Serializable
data class MemberInfo(
    val pubkey: String,
    val displayName: String,
    val upiId: String? = null
)

/**
 * Payload when a new member joins the group (or is added as an offline/ghost member).
 */
@Serializable
data class MemberJoinedPayload(
    val pubkey: String,
    val displayName: String,
    val joinedAt: Long,
    val upiId: String? = null,
    val claimedGhostPubkey: String? = null
)

/**
 * Payload when an expense is recorded.
 * Supports single payer (`payerPubkey`) as well as multiple payers (`payers`).
 */
@Serializable
data class ExpensePayload(
    val expenseId: String,
    val description: String,
    val totalAmount: Double,
    val currency: String,
    val payerPubkey: String,
    val splits: List<SplitItem>,
    val category: String = "General",
    val note: String = "",
    val splitType: String = "EQUAL",
    val payers: List<SplitItem> = emptyList()
)

/**
 * Payload when a previously recorded expense is voided/reversed on the immutable blockchain.
 */
@Serializable
data class ExpenseVoidPayload(
    val voidId: String,
    val targetExpenseId: String,
    val targetDescription: String,
    val reason: String = "Voided by member"
)

/**
 * Payload when a debt is settled between two members.
 */
@Serializable
data class SettlementPayload(
    val settlementId: String,
    val payerPubkey: String,
    val payeePubkey: String,
    val amount: Double,
    val currency: String,
    val note: String = "Settled balance"
)
