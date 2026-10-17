# PeerSplit V2 — Architecture & Implementation Plan

## 1. Initial Repository Inspection Findings

### 1.1 Existing Architecture Overview (V1)
- **Ledger Layer (`com.peersplit.app.blockchain`):**
  - `Block.kt`, `BlockPayload.kt`, `BlockchainEngine.kt`, `DebtSettlementEngine.kt`.
  - Uses a linear hash-chained block structure (`index`, `prevHash`, `blockHash`, `signature`, plus `originPubkey`, `originTimestamp`, `originSignature` added in the v6 hardening patch).
  - Concurrent offline edits cause forks at the same `index`; `P2PSyncManager.drainPendingBlocks()` previously rolled back losing branches (`deleteBlocksFromIndex`) and re-minted/re-signed displaced blocks on top of the winning tip.
  - Financial amounts in `ExpensePayload`, `SettlementPayload`, and `SplitItem` are stored as `Double` and converted to `Long` cents via `(amount * 100.0).roundToLong()` inside `DebtSettlementEngine.kt`.
- **Storage Layer (`com.peersplit.app.data.local`):**
  - `AppDatabase.kt` is currently at **Room version 6** (`peersplit_ledger.db`) with explicit migrations `MIGRATION_1_2` through `MIGRATION_5_6`.
  - Existing tables: `groups` (`GroupEntity`), `members` (`MemberEntity`), and `blocks` (`BlockEntity`, which includes `isSynced` boolean flag).
  - Note: `.fallbackToDestructiveMigrationOnDowngrade()` is still present in `AppDatabase.kt` and will be removed in V2 (`version = 7`) so no destructive migration fallback of any kind remains.
- **Cryptography (`com.peersplit.app.crypto`):**
  - `KeyManager.kt`: BouncyCastle `Ed25519KeyPairGenerator` and `Ed25519Signer` for 32-byte Ed25519 keys and 64-byte signatures. Private keys are encrypted at rest using Android Keystore (`AES/GCM/NoPadding`, 256-bit key).
  - `Bip340Util.kt`: Secp256k1 Schnorr signatures (BIP-340 / NIP-01) used for outer `NostrEvent` signing and verification.
  - `EncryptionUtil.kt`: `AES/GCM/NoPadding` (256-bit key, 12-byte random IV, 128-bit authentication tag) for group payload encryption. Note: `EncryptionUtil` currently uses `android.util.Base64`; we will make its core byte-level and Base64 methods JVM-testable (`java.util.Base64` compatible) and add Associated Authenticated Data (AAD) / epoch key derivation support.
- **Transport & Sync (`com.peersplit.app.network`):**
  - `NostrEvent.kt`, `NostrClient.kt`, `RelayPool.kt`, `P2PSyncManager.kt`.
  - Events are broadcast over 5 default Nostr relays using `kind = 1` with `#g` (`groupId`) and `#t` (`peersplit_<groupId>`) tags.
  - `NostrClient` emits `OK` event IDs via `acksFlow`, and `RelayPool` aggregates them via `ackedEventIds`.
- **Baseline Build & Test Status:**
  - `./gradlew assembleDebug bundleRelease` and `./gradlew testDebugUnitTest` pass cleanly on JDK 17 (`4` test classes: `BlockchainEngineTest`, `DebtSettlementEngineTest`, `Bip340UtilTest`, `CryptoTest`).

---

## 2. V2 Target Architecture: Immutable Signed-Operation DAG & Deterministic Reconciliation

### 2.1 Stage B — Versioned Signed-Operation Protocol (`com.peersplit.app.protocol`)
We will create a pure Kotlin, deterministic, domain-separated protocol package `com.peersplit.app.protocol`:
- **`ProtocolVersion.kt`**: Defines `PROTOCOL_VERSION_V1 = 1`, `PROTOCOL_VERSION_V2 = 2`, domain separation tags (`"PeerSplit/V2/Operation"`, `"PeerSplit/V2/Signature"`, `"PeerSplit/V2/StateDigest"`), and strict size/collection limits.
- **`OperationId.kt`**: Value/helper class enforcing 64-character lowercase hex SHA-256 operation IDs (`SHA256("PeerSplit/V2/Operation" || canonicalUnsignedBytes)`).
- **`OperationPayload.kt`**: `@Serializable` sealed interface supporting all PeerSplit business features in exact integer minor units (`Long`):
  - `GenesisCreated`: Establishes group ID, name, currency, creator public key, initial members, and key epoch `0`.
  - `ExpenseCreated`: `expenseId`, `amountMinor: Long`, `currency: String`, `paidBy: String`, `payers: Map<String, Long>`, `splits: Map<String, Long>`, `description: String`, `category: String`, `note: String`, `splitType: String`, `expenseDateMillis: Long`.
  - `ExpenseUpdated`: `expenseId`, `expectedRevisionId: String` (the `operationId` of the prior active revision, providing Compare-And-Swap semantics), `replacement: ExpenseCreated`.
  - `ExpenseVoided`: `expenseId`, `expectedRevisionId: String`, `reason: String`.
  - `SettlementRecorded`: `settlementId`, `fromMember: String`, `toMember: String`, `amountMinor: Long`, `currency: String`, `note: String`.
  - `MemberAdded`: `memberPublicKey: String`, `displayName: String`, `role: String`, `upiId: String?`, `phone: String?`, `claimedGhostPubkey: String?`, `newKeyEpoch: Int`.
  - `MemberRemoved`: `memberPublicKey: String`, `reason: String`, `newKeyEpoch: Int`.
  - `LegacyV1Checkpoint`: Explicitly labeled, non-forged container importing verified V1 chain state (`v1TipHash`, `v1BlockCount`, `migratedExpenses`, `migratedSettlements`, `migratedMembers`, `migratedAliases`) into a V2 DAG root.
- **`SignedOperation.kt`**: Immutable envelope (`protocolVersion`, `groupId`, `authorPublicKey`, `parents: List<String>`, `logicalClock: Long`, `keyEpoch: Int`, `payload: OperationPayload`, `operationId: String`, `signature: String`).
- **`OperationCanonicalizer.kt`**: Deterministic binary/UTF-8 length-prefixed canonical encoder:
  - Normalizes strings with Unicode NFC, rejects control characters (except `\n` in notes), sorts `parents` lexicographically, sorts all `Map` keys (`splits`, `payers`) lexicographically, and encodes integers in explicit big-endian 64-bit/32-bit format or canonical length-prefixed UTF-8 records so byte order can **never** vary across JVMs or JSON libraries.
- **`OperationSigner.kt` & `OperationVerifier.kt`**:
  - Computes `operationId = SHA256("PeerSplit/V2/Operation" || canonicalUnsignedBytes)`.
  - Signs `SHA256("PeerSplit/V2/Signature" || operationIdBytes || canonicalUnsignedBytes)` using Ed25519 (`KeyManager.signHex`).
  - Independently verifies canonical encoding, `operationId` match, and Ed25519 signature (`KeyManager.verifyHex`).
- **`OperationValidator.kt`**:
  - Validates structural and monetary invariants: `amountMinor > 0`, no `Long` addition overflow (`Math.addExact`), `splits.values.sum() == amountMinor`, `payers.values.sum() == amountMinor`, valid ISO currency matching group currency, non-empty strings within max length bounds, no duplicate parent IDs, and `logicalClock >= 1`.

### 2.2 Stage C — Deterministic Reconciliation Engine (`com.peersplit.app.reconciliation`)
A pure Kotlin package with zero Android/Room/network dependencies:
- **`CausalDependencyResolver.kt`**:
  - Partitions incoming operations into causally ready operations, operations with missing parents (`PendingOperation`), and operations with cycles or rejected ancestors (`RejectedOperation`).
  - Validates that every operation's `logicalClock > max(parent.logicalClock)`.
- **`OperationOrdering.kt`**:
  - Performs deterministic Kahn's algorithm topological sort over the causal DAG.
  - Breaks ties between causally concurrent operations deterministically using `(logicalClock ASC, operationId ASC)`.
- **`OperationAuthorization.kt`**:
  - Evaluates authorization against the **causal ancestor membership state** (and deterministic total-order epoch rules for concurrent membership removals):
  - If a `MemberRemoved(target)` operation is causally prior to an operation by `target`, `target`'s operation is rejected (`UNAUTHORIZED_REMOVED_MEMBER`).
  - If `MemberRemoved(target)` and an operation `op` by `target` are causally concurrent (neither is an ancestor of the other), **removal wins deterministically** unless `op` is an ancestor of an accepted operation signed by an active admin/creator. Thus a removed member cannot backdate `logicalClock` or reference old parent IDs to inject expenses after removal.
- **`OperationConflictResolver.kt`**:
  - Compare-And-Swap (`expectedRevisionId`) revision tracking for every `expenseId`:
    - `ExpenseCreated` sets initial `revisionId = op.operationId`. Duplicate `expenseId` creation with a different `operationId` is rejected (`DUPLICATE_EXPENSE_ID`).
    - `ExpenseVoided` and `ExpenseUpdated` must reference `expectedRevisionId == currentRevisionId`.
    - If two concurrent operations target the same `expectedRevisionId`:
      - **Concurrent Void vs. Edit:** `ExpenseVoided` takes precedence or deterministic winner by `(logicalClock ASC, operationId ASC)` is applied and the losing operation is recorded in `conflicts` and `rejectedOperations` (`CONCURRENT_REVISION_CONFLICT`)—never silently merged or mutated.
      - **Edit on already voided expense:** Rejected (`EXPENSE_ALREADY_VOIDED`).
    - Duplicate `settlementId` with different `operationId` is rejected (`DUPLICATE_SETTLEMENT_ID`).
- **`LedgerProjection.kt`, `LedgerValidationResult.kt`, `LedgerReconciliationEngine.kt`**:
  - Computes exact integer balances (`Map<String, Long>` in minor units) and min-cash-flow settlement suggestions in minor units.
  - Produces a deterministic cryptographic `stateDigest` (`SHA256("PeerSplit/V2/StateDigest" || acceptedOpIds || memberBalances || activeExpenseRevisions)`).

### 2.3 Stage D — Room Database V2 Storage (`MIGRATION_6_7`) & Repository Integration
- Upgrade `AppDatabase` from **version 6 to version 7** (`MIGRATION_6_7`) with zero destructive fallbacks:
  - `signed_operations` (`SignedOperationEntity`)
  - `operation_dependencies` (`OperationDependencyEntity`)
  - `expense_projections` (`ExpenseProjectionEntity`)
  - `balance_projections` (`BalanceProjectionEntity`)
  - `outbox_operations` (`OutboxOperationEntity`)
  - `relay_deliveries` (`RelayDeliveryEntity`)
  - `group_protocol_metadata` (`GroupProtocolEntity`)
- Update `ExpenseRepository.kt` to write V2 `SignedOperation` records and durable `OutboxOperationEntity` rows atomically inside `database.withTransaction`, reconcile the group via `LedgerReconciliationEngine`, and materialize projections while keeping all existing ViewModel/Compose flows 100% compatible.

### 2.4 Stage E — P2P Synchronization, Durable Outbox & Relay Delivery Tracking
- Update `P2PSyncManager.kt` to broadcast and receive encrypted V2 envelopes (`"v": 2`, `keyEpoch`, `operationId`, `ciphertext`) alongside legacy V1 support.
- Persist per-relay delivery acknowledgments in `relay_deliveries`, implement exponential backoff + jitter retry scheduling, add missing causal parent requests (`REQ` by `#op` / `#g`), and schedule background outbox flushing via WorkManager / coroutine workers.

### 2.5 Stage F — Safe V1-to-V2 Migration, Key Epochs & Comprehensive Test Suite
- Implement `V1ToV2MigrationManager` that verifies existing V1 blockchains, preserves all original V1 rows in `blocks`, and creates an explicit `LegacyV1Checkpoint` + `GenesisCreated` root for V2 without forging signatures.
- Add comprehensive unit, property-based, and multi-device randomized permutation convergence tests.
