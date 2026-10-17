package com.peersplit.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockType

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
    val creatorPubkey: String,
    val groupKeyBase64: String, // AES-256 group encryption key
    val isSecret: Boolean = false,
    val secretPinHash: String? = null,
    val isPersonalSolo: Boolean = false,
    val monthlyBudget: Double = 0.0,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "members",
    indices = [Index(value = ["groupId", "pubkey"], unique = true)]
)
data class MemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val pubkey: String,
    val displayName: String,
    val upiId: String? = null,
    val phone: String? = null
)

@Entity(
    tableName = "blocks",
    indices = [
        Index(value = ["groupId", "index"], unique = true),
        Index(value = ["groupId", "blockHash"], unique = true)
    ]
)
data class BlockEntity(
    @PrimaryKey val blockHash: String,
    val index: Long,
    val groupId: String,
    val prevHash: String,
    val timestamp: Long,
    val creatorPubkey: String,
    val blockType: String,
    val payloadJson: String,
    val signature: String,
    val isVerified: Boolean = true,
    val originPubkey: String? = null,
    val originTimestamp: Long = 0L,
    val originSignature: String? = null,
    val isSynced: Boolean = true
) {
    fun toDomainBlock(): Block {
        return Block(
            index = index,
            groupId = groupId,
            prevHash = prevHash,
            timestamp = timestamp,
            creatorPubkey = creatorPubkey,
            blockType = BlockType.valueOf(blockType),
            payloadJson = payloadJson,
            blockHash = blockHash,
            signature = signature,
            originPubkey = originPubkey,
            originTimestamp = originTimestamp,
            originSignature = originSignature
        )
    }

    companion object {
        fun fromDomainBlock(
            block: Block,
            isVerified: Boolean = true,
            isSynced: Boolean = true
        ): BlockEntity {
            return BlockEntity(
                blockHash = block.blockHash,
                index = block.index,
                groupId = block.groupId,
                prevHash = block.prevHash,
                timestamp = block.timestamp,
                creatorPubkey = block.creatorPubkey,
                blockType = block.blockType.name,
                payloadJson = block.payloadJson,
                signature = block.signature,
                isVerified = isVerified,
                originPubkey = block.originPubkey,
                originTimestamp = block.originTimestamp,
                originSignature = block.originSignature,
                isSynced = isSynced
            )
        }
    }
}

/**
 * Immutable V2 signed operation persisted in Room.
 * Once inserted, its cryptographic fields (`operationId`, `signature`, `payloadJson`, `parentsJson`)
 * are never overwritten with modified content.
 */
@Entity(
    tableName = "signed_operations",
    indices = [
        Index(value = ["groupId", "logicalClock"]),
        Index(value = ["groupId", "status"]),
        Index(value = ["authorPublicKey"])
    ]
)
data class SignedOperationEntity(
    @PrimaryKey val operationId: String,
    val protocolVersion: Int,
    val groupId: String,
    val authorPublicKey: String,
    val parentsJson: String,
    val logicalClock: Long,
    val payloadType: String,
    val payloadJson: String,
    val signature: String,
    val keyEpoch: Int = 0,
    val createdAtMillis: Long = 0L,
    val receivedAtMillis: Long = System.currentTimeMillis(),
    val status: String = STATUS_PENDING, // ACCEPTED, PENDING, REJECTED
    val rejectionReason: String? = null
) {
    companion object {
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_REJECTED = "REJECTED"
    }
}

/**
 * Explicit parent-child causal dependency edge in the V2 operation DAG.
 */
@Entity(
    tableName = "operation_dependencies",
    primaryKeys = ["childOperationId", "parentOperationId"],
    indices = [
        Index(value = ["groupId", "parentOperationId"]),
        Index(value = ["groupId", "childOperationId"])
    ]
)
data class OperationDependencyEntity(
    val groupId: String,
    val childOperationId: String,
    val parentOperationId: String
)

/**
 * Materialized V2 expense projection derived deterministically from the reconciled operation DAG.
 */
@Entity(
    tableName = "expense_projections",
    primaryKeys = ["groupId", "expenseId"],
    indices = [
        Index(value = ["groupId", "expenseDateMillis"]),
        Index(value = ["groupId", "isVoided"])
    ]
)
data class ExpenseProjectionEntity(
    val groupId: String,
    val expenseId: String,
    val originOperationId: String,
    val currentRevisionOperationId: String,
    val authorPublicKey: String,
    val logicalClock: Long,
    val amountMinor: Long,
    val currency: String,
    val paidBy: String,
    val payersJson: String,
    val splitsJson: String,
    val description: String,
    val category: String,
    val note: String,
    val splitType: String,
    val expenseDateMillis: Long,
    val isVoided: Boolean = false,
    val voidedByOperationId: String? = null,
    val voidReason: String? = null
)

/**
 * Materialized V2 member net balance projection in exact integer minor units (`Long`).
 */
@Entity(
    tableName = "balance_projections",
    primaryKeys = ["groupId", "memberPublicKey"],
    indices = [Index(value = ["groupId"])]
)
data class BalanceProjectionEntity(
    val groupId: String,
    val memberPublicKey: String,
    val balanceMinor: Long,
    val currency: String,
    val stateDigest: String,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Durable outgoing operation queue entry.
 * Stores the exact pre-signed, encrypted wire envelope so retries never re-sign or mutate the operation.
 */
@Entity(
    tableName = "outbox_operations",
    indices = [
        Index(value = ["status", "nextAttemptAtMillis"]),
        Index(value = ["groupId"]),
        Index(value = ["nostrEventId"], unique = true)
    ]
)
data class OutboxOperationEntity(
    @PrimaryKey val operationId: String,
    val groupId: String,
    val nostrEventId: String,
    val signedEventJson: String,
    val keyEpoch: Int = 0,
    val attemptCount: Int = 0,
    val nextAttemptAtMillis: Long = 0L,
    val status: String = STATUS_QUEUED, // QUEUED, ACKED_PARTIAL, ACKED_ALL, FAILED_PERMANENT
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_ACKED_PARTIAL = "ACKED_PARTIAL"
        const val STATUS_ACKED_ALL = "ACKED_ALL"
        const val STATUS_FAILED_PERMANENT = "FAILED_PERMANENT"
    }
}

/**
 * Per-relay delivery acknowledgment tracking for outgoing V2 operations.
 */
@Entity(
    tableName = "relay_deliveries",
    primaryKeys = ["operationId", "relayUrl"],
    indices = [
        Index(value = ["nostrEventId"]),
        Index(value = ["operationId", "accepted"])
    ]
)
data class RelayDeliveryEntity(
    val operationId: String,
    val relayUrl: String,
    val nostrEventId: String,
    val accepted: Boolean,
    val relayMessage: String? = null,
    val ackedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Per-group protocol version, trusted genesis anchor, key epoch, and synchronization checkpoint state.
 */
@Entity(
    tableName = "group_protocol_metadata",
    indices = [Index(value = ["protocolVersion"])]
)
data class GroupProtocolEntity(
    @PrimaryKey val groupId: String,
    val protocolVersion: Int,
    val genesisOperationId: String? = null,
    val currentKeyEpoch: Int = 0,
    val latestStateDigest: String = "",
    val maxLogicalClock: Long = 0L,
    val headOperationIdsJson: String = "[]",
    val v1MigrationStatus: String = MIGRATION_NONE, // NONE, IN_PROGRESS, COMPLETED
    val v1MigratedTipHash: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val MIGRATION_NONE = "NONE"
        const val MIGRATION_IN_PROGRESS = "IN_PROGRESS"
        const val MIGRATION_COMPLETED = "COMPLETED"
    }
}

/**
 * Versioned group encryption keys for epoch-based forward secrecy.
 * Whenever a member is removed, the key epoch is incremented and a new AES-256 key is generated,
 * ensuring newly removed members cannot decrypt future group events.
 */
@Entity(
    tableName = "group_keys",
    primaryKeys = ["groupId", "keyEpoch"],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["groupId", "keyEpoch"])
    ]
)
data class GroupKeyEntity(
    val groupId: String,
    val keyEpoch: Int,
    val groupKeyBase64: String,
    val distributedToPubkeysJson: String = "[]",
    val createdAtMillis: Long = System.currentTimeMillis()
)

