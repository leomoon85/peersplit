package com.peersplit.app.reconciliation

import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.SignedOperation
import java.util.PriorityQueue

/**
 * Deterministic topological sorting of causally ready operations.
 *
 * Ordering guarantees:
 * 1. Every parent operation strictly precedes all of its causal descendants.
 * 2. Among causally concurrent operations (whose parents have all been emitted),
 *    ties are broken deterministically by:
 *    - Root operations (`GenesisCreated` / `LegacyV1Checkpoint`) first.
 *    - Membership removals (`MemberRemoved`) before concurrent financial operations at the same causal frontier,
 *      enforcing the security policy that concurrent removal preempts unauthorized concurrent spending.
 *    - `logicalClock` ascending.
 *    - `operationId` lexicographically ascending.
 */
object OperationOrdering {

    private fun payloadPriority(payload: OperationPayload): Int = when (payload) {
        is OperationPayload.GenesisCreated -> 0
        is OperationPayload.LegacyV1Checkpoint -> 1
        is OperationPayload.MemberRemoved -> 2
        is OperationPayload.MemberAdded -> 3
        is OperationPayload.ExpenseCreated -> 4
        is OperationPayload.ExpenseUpdated -> 5
        is OperationPayload.ExpenseVoided -> 6
        is OperationPayload.SettlementRecorded -> 7
    }

    val DETERMINISTIC_TIE_BREAKER: Comparator<SignedOperation> = Comparator { a, b ->
        val prioCmp = payloadPriority(a.payload).compareTo(payloadPriority(b.payload))
        if (prioCmp != 0) return@Comparator prioCmp
        val clockCmp = a.logicalClock.compareTo(b.logicalClock)
        if (clockCmp != 0) return@Comparator clockCmp
        a.operationId.compareTo(b.operationId)
    }

    /**
     * Produces a unique, deterministic total order over `operations` that respects all causal edges.
     */
    fun sortTopologically(operations: Collection<SignedOperation>): List<SignedOperation> {
        if (operations.isEmpty()) return emptyList()

        val opsById = operations.associateBy { it.operationId }
        val inDegree = mutableMapOf<String, Int>()
        val children = mutableMapOf<String, MutableList<String>>()

        for (op in operations) {
            var deg = 0
            for (parentId in op.parents) {
                if (parentId in opsById) {
                    deg++
                    children.getOrPut(parentId) { mutableListOf() }.add(op.operationId)
                }
            }
            inDegree[op.operationId] = deg
        }

        val readyQueue = PriorityQueue(DETERMINISTIC_TIE_BREAKER)
        for (op in operations) {
            if ((inDegree[op.operationId] ?: 0) == 0) {
                readyQueue.add(op)
            }
        }

        val ordered = ArrayList<SignedOperation>(operations.size)
        while (readyQueue.isNotEmpty()) {
            val current = readyQueue.poll()!!
            ordered.add(current)

            val childIds = children[current.operationId].orEmpty()
            for (childId in childIds) {
                val newDeg = (inDegree[childId] ?: 1) - 1
                inDegree[childId] = newDeg
                if (newDeg == 0) {
                    opsById[childId]?.let { readyQueue.add(it) }
                }
            }
        }

        return ordered
    }
}
