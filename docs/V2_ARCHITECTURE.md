# PeerSplit V2 Architecture Specification

## 1. Executive Summary

PeerSplit V2 transitions the application from a fragile linear single-chain blockchain model with runtime fork-rebasing to a robust, distributed-systems-grade **Immutable Signed-Operation Directed Acyclic Graph (DAG) with a Deterministic Reconciliation Engine**.

Under PeerSplit V2:
- Multiple devices create expenses and settlements completely offline.
- Every operation is immutable, globally uniquely identified by a domain-separated SHA-256 digest, and cryptographically signed with Ed25519 by its original author.
- Operations synchronize asynchronously over Nostr relays (`kind: 29999`) with durable retry queues, exponential backoff with jitter, and per-relay delivery acknowledgment tracking.
- Network arrival order, duplicate delivery, and relay reordering have zero impact on the final accepted ledger state: devices with the identical set of valid operations deterministically compute the exact same balances, projections, and canonical 64-hex state digest.

---

## 2. Core Subsystems & Components

```
+-----------------------------------------------------------------------------------+
|                                 Presentation Layer                                |
|  Jetpack Compose Screens & ViewModels (AddExpense, GroupDetail, SettleUp, etc.)    |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                              ExpenseRepository                                    |
|  Dual-Mode Orchestration: Mints V2 Operations, queries reactive Room Projections  |
+-----------------------------------------------------------------------------------+
        |                                                        |
        v                                                        v
+-----------------------------+        +--------------------------------------------+
|  Local Persistence (Room v7)|        |           P2P Transport (Nostr)            |
|  - signed_operations        |        |  P2PSyncManager & RelayPool                |
|  - operation_dependencies   |        |  - Encrypted WireOperationEnvelope         |
|  - expense_projections      |        |  - Outbox retry with backoff & jitter      |
|  - balance_projections      |        |  - Delivery acknowledgment tracking        |
|  - outbox_operations        |        +--------------------------------------------+
|  - relay_deliveries         |                                  |
|  - group_protocol_metadata  |                                  v
+-----------------------------+                  Incoming Nostr Events
        |                                                        |
        +----------------------------+---------------------------+
                                     |
                                     v
+-----------------------------------------------------------------------------------+
|                     Deterministic Reconciliation Engine                           |
|                                                                                   |
|  1. Deduplicate & Verify Invariants (OperationValidator & OperationVerifier)     |
|  2. Causal Dependency Resolution (CausalDependencyResolver)                       |
|  3. Deterministic Topological Ordering (OperationOrdering - Kahn's Algorithm)    |
|  4. Causal Membership Authorization (OperationAuthorization)                      |
|  5. Conflict Resolution & CAS Revisions (OperationConflictResolver)              |
|  6. Pure Integer Minor-Unit Accounting (LedgerProjection - Zero Floats)           |
|  7. Canonical State Digest (SHA-256 over sorted accepted operations & balances)   |
+-----------------------------------------------------------------------------------+
                                     |
                                     v
+-----------------------------------------------------------------------------------+
|                          ReconciliationProjector                                  |
|  Atomic Room transaction materializing expense_projections & balance_projections  |
+-----------------------------------------------------------------------------------+
```

---

## 3. Data Structures

### 3.1 Immutable Signed Operation Envelope (`SignedOperation`)
Every operation stored and transmitted has an immutable envelope:
- `protocolVersion`: Integer (2 for V2).
- `groupId`: Globally unique 64-hex group identifier.
- `authorPublicKey`: Lowercase 64-hex Ed25519 public key of the author.
- `parents`: Deterministically sorted list of parent `operationId` references (up to 32 parents).
- `logicalClock`: Monotonically increasing Lamport clock (`clock > max(parent.clock)`).
- `payload`: Sealed polymorphic payload (`OperationPayload`).
- `operationId`: SHA-256(`"PeerSplit/V2/Operation"` || 0x00 || `canonicalUnsignedBytes`).
- `signature`: Ed25519 signature of domain-separated signing digest.
- `keyEpoch`: Symmetric encryption key rotation epoch.
- `createdAtMillis`: Wall-clock informational timestamp.

### 3.2 Operation Payloads (`OperationPayload`)
- `GenesisCreated`: Root group anchor defining initial members, currency, and creator.
- `ExpenseCreated`: Pure integer minor unit expense with single or multi-payer map and split map.
- `ExpenseUpdated`: Compare-and-swap replacement specifying `expectedRevisionId`.
- `ExpenseVoided`: Compare-and-swap void specifying `expectedRevisionId` and reason.
- `SettlementRecorded`: Direct debt settlement transfer in minor units.
- `MemberAdded`: Adding an active member or claiming an offline ghost member.
- `MemberRemoved`: Administrative removal rotating group key epoch.
- `LegacyV1Checkpoint`: Explicit migration checkpoint importing verified V1 linear chain tip hash and balances without forging signatures.

---

## 4. Deterministic Reconciliation Engine

The engine is implemented in pure Kotlin (`DefaultLedgerReconciliationEngine`) with no Android, Room, or UI dependencies:

1. **Envelope Validation:** Verifies protocol version, string length limits, currency support, and collection bounds.
2. **Cryptographic Verification:** Validates content-addressed `operationId` against canonical byte encoding and checks Ed25519 signature.
3. **Causal Dependency Resolution:** Detects DAG cycles, partitions missing parents into `pendingOperations`, and enforces Lamport clock monotonicity.
4. **Topological Ordering:** Employs Kahn's algorithm with deterministic tie-breaking: `logicalClock ASC`, then `operationId ASC`.
5. **Removal-Precedence Authorization:** Active members are tracked causally. If a member removal is authored by an authorized administrator, any concurrent operations authored by the removed member are deterministically rejected even if they referenced an earlier clock.
6. **Conflict Resolution:**
   - Duplicate expense or settlement IDs: First operation in topological order wins; duplicate is rejected with `DUPLICATE_EXPENSE_ID` or `DUPLICATE_SETTLEMENT_ID`.
   - CAS Revisions: `expectedRevisionId` must match active revision. If concurrent revisions conflict, lower `(logicalClock, operationId)` succeeds while competing edit is rejected with `REVISION_MISMATCH_CONFLICT`.
   - Void Dominance: Any authorized `ExpenseVoided` targeting `expectedRevisionId` permanently dominates concurrent edits.
7. **Zero-Sum Integer Accounting:** All balances are computed in `Long` minor units (`paise`/`cents`). Strict `sum(balances.values) == 0L` is asserted.
8. **State Digest Generation:** Emits a deterministic 64-hex digest over accepted operations and balances.

---

## 5. Storage Schema (Room v7)

The database was upgraded from version 6 to version 7 without destructive fallback (`MIGRATION_6_7`):
- `signed_operations`: Primary store of immutable signed operations.
- `operation_dependencies`: Index of child-parent DAG relationships.
- `expense_projections`: Materialized projection of active and voided expenses for instant UI queries.
- `balance_projections`: Materialized projection of member net balances in integer minor units.
- `outbox_operations`: Durable queue storing pre-signed encrypted Nostr wire events.
- `relay_deliveries`: Delivery confirmation and relay rejection tracking per relay.
- `group_protocol_metadata`: Tracks group protocol version, genesis operation ID, state digest, and migration state.

---

## 6. Migration & Backward Compatibility

- Existing V1 linear block tables (`blocks`) are never deleted or modified.
- V1 groups are upgraded via `V1ToV2MigrationManager` by minting a signed `LegacyV1Checkpoint` operation referencing the verified V1 tip hash.
- Unmigrated V1 groups continue to be read and displayed normally via legacy fallback in `computeGroupDetail`.
