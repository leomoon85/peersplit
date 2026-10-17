package com.peersplit.protocol

import kotlinx.serialization.Serializable

/**
 * Wire-format envelope used to transmit encrypted V2 operations across Nostr relays or QR codes.
 */
@Serializable
data class WireOperationEnvelope(
    val protocolVersion: Int,
    val groupId: String,
    val operationId: String,
    val keyEpoch: Int,
    val encryptedPayloadBase64: String
)
