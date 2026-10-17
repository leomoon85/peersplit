# PeerSplit V2 Test Report

## 1. Test Suite Summary

- **Task Executed:** `testDebugUnitTest`
- **Result:** `BUILD SUCCESSFUL`
- **Total Test Cases Executed:** 17
- **Passed:** 17
- **Failed:** 0
- **Execution Time:** ~15 seconds

---

## 2. Test Coverage Breakdown

### 2.1 Cryptographic & Protocol Envelope Tests (`SignedOperationProtocolTest.kt`)
- **Canonical Serialization Determinism:** Proved that parent ordering, map keys, Unicode NFC normalization, and binary encoding produce identical bytes across runs.
- **Content-Derived Operation ID:** Validated that modifying any field (e.g., amount, split, parent, timestamp) changes the 64-hex operation ID.
- **Ed25519 Domain-Separated Signatures:** Verified signature creation and verification with invalid signature detection.
- **Structural Invariant Validation:** Verified rejection of unsupported protocol versions, invalid currencies, arithmetic overflows, and split mismatch errors.
- **Deterministic Equal Split Allocation:** Verified remainder allocation with zero floating point drift.

### 2.2 Deterministic Reconciliation Engine Tests (`LedgerReconciliationEngineTest.kt`)
- **Three-Device Offline Expense Convergence:** Alice, Bob, and Charlie create independent expenses offline. All 6 arrival permutations (`3! = 6`) converge to identical net balances and identical 64-hex state digests.
- **Randomized Multi-Device Permutations:** 20 randomized shuffles of concurrent operations produce identical state digests.
- **Compare-And-Swap (CAS) Expense Revisions:** Successful edit advances revision; concurrent edit targeting stale `expectedRevisionId` is deterministically rejected with `REVISION_MISMATCH_CONFLICT`.
- **Void Dominance Policy:** Concurrent void and edit targeting the same `expectedRevisionId` deterministically results in Void winning and edit rejected with `EXPENSE_ALREADY_VOIDED`.
- **Duplicate Prevention:** Replaying the same `expenseId` with a different operation ID is rejected with `DUPLICATE_EXPENSE_ID`.
- **Removal-Precedence Authorization Policy:** Operations created by a removed member referencing old logical clocks are deterministically rejected with `UNAUTHORIZED_AUTHOR`.
- **DAG Cycle Detection:** Causal cycle loops are detected and rejected with `CAUSAL_CYCLE_DETECTED`.
- **Causal Missing Parent Handling:** Operations referencing unobserved parents are cleanly partitioned into `pendingOperations` without crashing or corrupting state.

### 2.3 Legacy V1 to V2 Migration Tests (`V1ToV2MigrationTest.kt`)
- **V1 Linear Chain Checkpoint Ingestion:** Verified that a synthetic 5-block V1 linear chain (Genesis -> MemberJoins -> Expense -> Settlement) converts into a verified `LegacyV1Checkpoint`.
- **Financial Balance Equality:** Net balances computed by V2 engine match V1 `DebtSettlementEngine` to the exact cent/paise (Alice: ₹150.00, Bob: -₹50.00, Charlie: -₹100.00).
- **Zero-Sum Accounting Check:** Verified strict zero-sum sum across all members (`sum == 0L`).
- **Post-Migration Continuation:** Validated that new V2 expenses chained on top of the checkpoint compute smoothly.
