package com.peersplit.protocol.interfaces

import com.peersplit.protocol.SignedOperation
import kotlinx.coroutines.flow.Flow

/**
 * Cross-platform network transport interface for relay synchronization.
 * Implemented on Android using NostrClient over OkHttp WebSockets, and on iOS using URLSessionWebSocketTask.
 */
interface GroupRelayTransport {
    suspend fun publishOperation(operation: SignedOperation): Result<Unit>
    fun subscribeGroupOperations(groupId: String): Flow<SignedOperation>
    suspend fun acknowledgeDelivery(operationId: String, relayUrl: String)
}
