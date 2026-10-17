package com.peersplit.app.protocol

import kotlinx.serialization.Serializable

/**
 * Immutable, content-addressed, cryptographically signed operation in a PeerSplit V2 group DAG.
 *
 * Once created and signed by `authorPublicKey`, this operation is never mutated, re-indexed,
 * or re-signed by any peer device.
 */
@Serializable
data class SignedOperation(
    val protocolVersion: Int = ProtocolVersion.V2_SIGNED_OPERATIONS,
    val groupId: String,
    val authorPublicKey: String,
    val parents: List<String>,
    val logicalClock: Long,
    val payload: OperationPayload,
    val operationId: String,
    val signature: String,
    val keyEpoch: Int = 0,
    val createdAtMillis: Long = 0L
)
