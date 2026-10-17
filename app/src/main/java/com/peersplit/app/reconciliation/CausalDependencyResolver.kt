package com.peersplit.app.reconciliation

import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.SignedOperation

/**
 * Result of resolving causal parent-child dependencies across a collection of [SignedOperation]s.
 */
data class CausalResolutionResult(
    val causallyReady: List<SignedOperation>,
    val pending: List<PendingOperation>,
    val rejected: List<RejectedOperation>,
    val ancestorsOf: Map<String, Set<String>>,
    val childrenOf: Map<String, Set<String>>
)

/**
 * Resolves causal dependencies in the operation DAG:
 * - Partitions operations with missing parents into `pending`.
 * - Detects and rejects cycles (`CAUSAL_CYCLE_DETECTED`).
 * - Rejects operations referencing rejected parents (`PARENT_REJECTED`).
 * - Enforces strict logicalClock monotonicity (`child.logicalClock > parent.logicalClock`).
 */
object CausalDependencyResolver {

    fun resolve(
        validEnvelopeOps: Map<String, SignedOperation>,
        preRejectedIds: Set<String>
    ): CausalResolutionResult {
        val pending = mutableListOf<PendingOperation>()
        val rejected = mutableListOf<RejectedOperation>()
        val rejectedIds = preRejectedIds.toMutableSet()

        // Iterative fixed-point classification
        val remaining = LinkedHashMap(validEnvelopeOps)
        val ready = LinkedHashMap<String, SignedOperation>()

        var changed = true
        while (changed) {
            changed = false
            val iterator = remaining.entries.iterator()
            while (iterator.hasNext()) {
                val (_, op) = iterator.next()

                // 1. Check if any parent was already rejected
                val rejectedParent = op.parents.firstOrNull { it in rejectedIds }
                if (rejectedParent != null) {
                    rejected.add(
                        RejectedOperation(
                            operation = op,
                            reason = RejectionReason.PARENT_REJECTED,
                            detail = "Parent operation $rejectedParent was rejected"
                        )
                    )
                    rejectedIds.add(op.operationId)
                    iterator.remove()
                    changed = true
                    continue
                }

                // 2. Root operations (GenesisCreated or LegacyV1Checkpoint) have no parents
                if (op.parents.isEmpty()) {
                    ready[op.operationId] = op
                    iterator.remove()
                    changed = true
                    continue
                }

                // 3. Check if all parents are already in `ready`
                if (op.parents.all { it in ready }) {
                    // Verify strict logicalClock monotonicity over all parents
                    val maxParentClock = op.parents.maxOf { ready[it]!!.logicalClock }
                    if (op.logicalClock <= maxParentClock) {
                        rejected.add(
                            RejectedOperation(
                                operation = op,
                                reason = RejectionReason.NON_MONOTONIC_LOGICAL_CLOCK,
                                detail = "logicalClock (${op.logicalClock}) must be > max parent clock ($maxParentClock)"
                            )
                        )
                        rejectedIds.add(op.operationId)
                    } else {
                        ready[op.operationId] = op
                    }
                    iterator.remove()
                    changed = true
                }
            }
        }

        // Any remaining operations either have missing external parents or form a cycle among themselves
        for ((_, op) in remaining) {
            val missingExternal = op.parents.filter { it !in validEnvelopeOps && it !in rejectedIds }.toSet()
            if (missingExternal.isNotEmpty()) {
                pending.add(PendingOperation(operation = op, missingParentIds = missingExternal))
            } else {
                // If an operation's parents are in `remaining`, check if it's waiting on a pending ancestor or in a cycle
                val hasCycle = detectCycle(op.operationId, remaining)
                if (hasCycle) {
                    rejected.add(
                        RejectedOperation(
                            operation = op,
                            reason = RejectionReason.CAUSAL_CYCLE_DETECTED,
                            detail = "Operation is part of a causal dependency cycle"
                        )
                    )
                } else {
                    val transitiveMissing = collectTransitiveMissingParents(op.operationId, remaining, validEnvelopeOps, rejectedIds)
                    pending.add(PendingOperation(operation = op, missingParentIds = transitiveMissing))
                }
            }
        }

        // Precompute transitive ancestors and direct children for all ready operations
        val childrenOf = mutableMapOf<String, MutableSet<String>>()
        for (op in ready.values) {
            for (p in op.parents) {
                childrenOf.getOrPut(p) { mutableSetOf() }.add(op.operationId)
            }
        }

        val ancestorsOf = mutableMapOf<String, Set<String>>()
        fun getAncestors(opId: String): Set<String> {
            ancestorsOf[opId]?.let { return it }
            val op = ready[opId] ?: return emptySet()
            val set = mutableSetOf<String>()
            for (p in op.parents) {
                set.add(p)
                set.addAll(getAncestors(p))
            }
            ancestorsOf[opId] = set
            return set
        }
        for (opId in ready.keys) {
            getAncestors(opId)
        }

        return CausalResolutionResult(
            causallyReady = ready.values.toList(),
            pending = pending.sortedBy { it.operation.operationId },
            rejected = rejected,
            ancestorsOf = ancestorsOf,
            childrenOf = childrenOf
        )
    }

    private fun detectCycle(startId: String, graph: Map<String, SignedOperation>): Boolean {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun dfs(currId: String): Boolean {
            if (currId in visiting) return true
            if (currId in visited) return false
            val node = graph[currId] ?: return false
            visiting.add(currId)
            for (p in node.parents) {
                if (p in graph && dfs(p)) return true
            }
            visiting.remove(currId)
            visited.add(currId)
            return false
        }

        return dfs(startId)
    }

    private fun collectTransitiveMissingParents(
        startId: String,
        remaining: Map<String, SignedOperation>,
        allValid: Map<String, SignedOperation>,
        rejectedIds: Set<String>
    ): Set<String> {
        val missing = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(startId)

        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            if (!visited.add(curr)) continue
            val op = remaining[curr] ?: continue
            for (p in op.parents) {
                if (p !in allValid && p !in rejectedIds) {
                    missing.add(p)
                } else if (p in remaining) {
                    queue.add(p)
                }
            }
        }
        return missing
    }
}
