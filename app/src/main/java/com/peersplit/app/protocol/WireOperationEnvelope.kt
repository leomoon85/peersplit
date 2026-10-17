package com.peersplit.app.protocol

import kotlinx.serialization.Serializable

/**
 * Transport-level encrypted payload envelope broadcast over Nostr relays.
 */
@Serializable
data class WireOperationEnvelope(
    val version: Int = ProtocolVersion.V2_SIGNED_OPERATIONS,
    val groupId: String,
    val operationId: String,
    val keyEpoch: Int = 0,
    val serializedOperationJson: String
)
