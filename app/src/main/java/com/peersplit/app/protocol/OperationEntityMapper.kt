package com.peersplit.app.protocol

import com.peersplit.app.data.local.entity.OperationDependencyEntity
import com.peersplit.app.data.local.entity.SignedOperationEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Bidirectional mapper between domain [SignedOperation] and Room [SignedOperationEntity].
 */
object OperationEntityMapper {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun payloadTypeName(payload: OperationPayload): String = when (payload) {
        is OperationPayload.GenesisCreated -> "GENESIS_CREATED"
        is OperationPayload.ExpenseCreated -> "EXPENSE_CREATED"
        is OperationPayload.ExpenseUpdated -> "EXPENSE_UPDATED"
        is OperationPayload.ExpenseVoided -> "EXPENSE_VOIDED"
        is OperationPayload.SettlementRecorded -> "SETTLEMENT_RECORDED"
        is OperationPayload.MemberAdded -> "MEMBER_ADDED"
        is OperationPayload.MemberRemoved -> "MEMBER_REMOVED"
        is OperationPayload.LegacyV1Checkpoint -> "LEGACY_V1_CHECKPOINT"
    }

    fun toEntity(
        operation: SignedOperation,
        status: String = SignedOperationEntity.STATUS_PENDING,
        rejectionReason: String? = null
    ): SignedOperationEntity {
        val parentsJson = json.encodeToString(ListSerializer(String.serializer()), operation.parents)
        val payloadJson = json.encodeToString(OperationPayload.serializer(), operation.payload)
        return SignedOperationEntity(
            operationId = operation.operationId,
            protocolVersion = operation.protocolVersion,
            groupId = operation.groupId,
            authorPublicKey = operation.authorPublicKey,
            parentsJson = parentsJson,
            logicalClock = operation.logicalClock,
            payloadType = payloadTypeName(operation.payload),
            payloadJson = payloadJson,
            signature = operation.signature,
            keyEpoch = operation.keyEpoch,
            createdAtMillis = operation.createdAtMillis,
            status = status,
            rejectionReason = rejectionReason
        )
    }

    fun toDependencyEntities(operation: SignedOperation): List<OperationDependencyEntity> {
        return operation.parents.map { parentId ->
            OperationDependencyEntity(
                groupId = operation.groupId,
                childOperationId = operation.operationId,
                parentOperationId = parentId
            )
        }
    }

    fun toDomain(entity: SignedOperationEntity): SignedOperation {
        val parents = json.decodeFromString(ListSerializer(String.serializer()), entity.parentsJson)
        val payload = json.decodeFromString(OperationPayload.serializer(), entity.payloadJson)
        return SignedOperation(
            protocolVersion = entity.protocolVersion,
            groupId = entity.groupId,
            authorPublicKey = entity.authorPublicKey,
            parents = parents,
            logicalClock = entity.logicalClock,
            payload = payload,
            operationId = entity.operationId,
            signature = entity.signature,
            keyEpoch = entity.keyEpoch,
            createdAtMillis = entity.createdAtMillis
        )
    }
}
