package com.peersplit.protocol.interfaces

import com.peersplit.protocol.SignedOperation
import com.peersplit.reconciliation.LedgerProjection
import com.peersplit.reconciliation.ReconciliationResult

/**
 * Cross-platform persistence interface for storing and retrieving signed operations.
 * Implemented on Android using Room SQLite DAOs, and on iOS using SwiftData / SQLite.
 */
interface OperationStore {
    suspend fun storeOperations(operations: List<SignedOperation>)
    suspend fun getOperationsForGroup(groupId: String): List<SignedOperation>
    suspend fun getOperationById(operationId: String): SignedOperation?
    suspend fun getPendingOperations(): List<SignedOperation>
    suspend fun markOperationDelivered(operationId: String, peerId: String)
}

/**
 * Cross-platform interface for storing and loading materialized ledger projections.
 */
interface LedgerProjectionStore {
    suspend fun loadProjection(groupId: String): ReconciliationResult?
    suspend fun saveProjection(groupId: String, result: ReconciliationResult)
}
