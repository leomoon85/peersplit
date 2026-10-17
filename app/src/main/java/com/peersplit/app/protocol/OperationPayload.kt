package com.peersplit.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical member descriptor used in Genesis and Legacy V1 Checkpoints.
 */
@Serializable
data class ProtocolMemberInfo(
    val publicKey: String,
    val displayName: String,
    val role: String = ROLE_MEMBER,
    val upiId: String? = null,
    val phone: String? = null,
    val claimedGhostPubkey: String? = null
) {
    companion object {
        const val ROLE_ADMIN = "ADMIN"
        const val ROLE_MEMBER = "MEMBER"
        const val ROLE_GHOST = "GHOST"
    }
}

/**
 * Sealed hierarchy of V2 operation payloads.
 * All monetary amounts are strictly represented as 64-bit integer minor units (e.g., paise/cents).
 */
@Serializable
sealed interface OperationPayload {

    /**
     * Root operation establishing the group's immutable identity, currency, creator, and initial membership.
     */
    @Serializable
    @SerialName("GENESIS_CREATED")
    data class GenesisCreated(
        val groupId: String,
        val groupName: String,
        val currency: String,
        val creatorPublicKey: String,
        val initialMembers: List<ProtocolMemberInfo>,
        val initialKeyEpoch: Int = 0
    ) : OperationPayload

    /**
     * Creates a new expense in integer minor units.
     * Supports both single payer (`paidBy`) and multi-payer (`payers` map of memberPublicKey -> amountMinor).
     */
    @Serializable
    @SerialName("EXPENSE_CREATED")
    data class ExpenseCreated(
        val expenseId: String,
        val amountMinor: Long,
        val currency: String,
        val paidBy: String,
        val splits: Map<String, Long>,
        val description: String,
        val payers: Map<String, Long> = emptyMap(),
        val category: String = "General",
        val note: String = "",
        val splitType: String = "EQUAL",
        val expenseDateMillis: Long = 0L
    ) : OperationPayload

    /**
     * Updates an existing expense using Compare-And-Swap (`expectedRevisionId`) semantics.
     * `expectedRevisionId` must match the `operationId` of the currently active revision of `expenseId`.
     */
    @Serializable
    @SerialName("EXPENSE_UPDATED")
    data class ExpenseUpdated(
        val expenseId: String,
        val expectedRevisionId: String,
        val replacement: ExpenseCreated
    ) : OperationPayload

    /**
     * Voids an existing expense using Compare-And-Swap (`expectedRevisionId`) semantics.
     */
    @Serializable
    @SerialName("EXPENSE_VOIDED")
    data class ExpenseVoided(
        val expenseId: String,
        val expectedRevisionId: String,
        val reason: String = "Voided by member"
    ) : OperationPayload

    /**
     * Records a direct settlement transfer between two members in integer minor units.
     */
    @Serializable
    @SerialName("SETTLEMENT_RECORDED")
    data class SettlementRecorded(
        val settlementId: String,
        val fromMember: String,
        val toMember: String,
        val amountMinor: Long,
        val currency: String,
        val note: String = "Direct Settlement"
    ) : OperationPayload

    /**
     * Adds a member (or claims an offline ghost member) and optionally advances the group key epoch.
     */
    @Serializable
    @SerialName("MEMBER_ADDED")
    data class MemberAdded(
        val memberPublicKey: String,
        val displayName: String,
        val role: String = ProtocolMemberInfo.ROLE_MEMBER,
        val upiId: String? = null,
        val phone: String? = null,
        val claimedGhostPubkey: String? = null,
        val newKeyEpoch: Int = 0
    ) : OperationPayload

    /**
     * Removes a member from the group and rotates the group key epoch.
     */
    @Serializable
    @SerialName("MEMBER_REMOVED")
    data class MemberRemoved(
        val memberPublicKey: String,
        val reason: String = "Removed from group",
        val newKeyEpoch: Int = 0
    ) : OperationPayload

    /**
     * Explicit, non-forged migration checkpoint importing a verified V1 linear blockchain history
     * into the V2 operation DAG. Preserves the exact V1 tip hash and verified state without
     * fabricating V2 signatures for historical V1 authors.
     */
    @Serializable
    @SerialName("LEGACY_V1_CHECKPOINT")
    data class LegacyV1Checkpoint(
        val v1TipBlockHash: String,
        val v1BlockCount: Long,
        val isLegacyChainFullyVerified: Boolean,
        val currency: String,
        val members: List<ProtocolMemberInfo>,
        val activeExpenses: List<ExpenseCreated>,
        val voidedExpenseIds: List<String>,
        val settlements: List<SettlementRecorded>,
        val ghostAliases: Map<String, String> = emptyMap()
    ) : OperationPayload
}
