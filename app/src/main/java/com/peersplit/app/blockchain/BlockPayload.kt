package com.peersplit.app.blockchain

import kotlinx.serialization.Serializable

/**
 * Supported types of blocks in a group's micro-ledger.
 */
enum class BlockType {
    GENESIS,
    MEMBER_JOIN,
    EXPENSE,
    SETTLEMENT
}

/**
 * Data payload for an individual split in an expense.
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
    val displayName: String
)

/**
 * Payload when a new member joins the group.
 */
@Serializable
data class MemberJoinedPayload(
    val pubkey: String,
    val displayName: String,
    val joinedAt: Long
)

/**
 * Payload when an expense is recorded.
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
    val note: String = ""
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
