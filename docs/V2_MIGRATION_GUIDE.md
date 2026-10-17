# PeerSplit V2 Migration Guide

## 1. Zero Destructive Data Loss Guarantee

PeerSplit V2 guarantees complete data preservation during upgrades:
- Existing Room tables (`groups`, `members`, `blocks`) are never dropped or mutated.
- `.fallbackToDestructiveMigrationOnDowngrade()` has been completely removed from `AppDatabase.getInstance()`.
- Legacy V1 blocks remain stored locally to serve as audit history and unmigrated fallback.

---

## 2. Room Schema Migration (v6 to v7)

The Room database version was incremented from `6` to `7`. The migration script `MIGRATION_6_7` executes the following DDL:

```sql
-- 1. Signed Operations Table
CREATE TABLE IF NOT EXISTS signed_operations (
    operationId TEXT NOT NULL PRIMARY KEY,
    protocolVersion INTEGER NOT NULL,
    groupId TEXT NOT NULL,
    authorPublicKey TEXT NOT NULL,
    parentsJson TEXT NOT NULL,
    logicalClock INTEGER NOT NULL,
    payloadType TEXT NOT NULL,
    payloadJson TEXT NOT NULL,
    signature TEXT NOT NULL,
    keyEpoch INTEGER NOT NULL,
    createdAtMillis INTEGER NOT NULL,
    receivedAtMillis INTEGER NOT NULL,
    status TEXT NOT NULL,
    rejectionReason TEXT
);
CREATE INDEX IF NOT EXISTS index_signed_operations_groupId_logicalClock_operationId ON signed_operations (groupId, logicalClock, operationId);
CREATE INDEX IF NOT EXISTS index_signed_operations_authorPublicKey ON signed_operations (authorPublicKey);
CREATE INDEX IF NOT EXISTS index_signed_operations_groupId_status ON signed_operations (groupId, status);

-- 2. Operation Dependencies Table
CREATE TABLE IF NOT EXISTS operation_dependencies (
    groupId TEXT NOT NULL,
    childOperationId TEXT NOT NULL,
    parentOperationId TEXT NOT NULL,
    PRIMARY KEY (childOperationId, parentOperationId)
);
CREATE INDEX IF NOT EXISTS index_operation_dependencies_groupId_parentOperationId ON operation_dependencies (groupId, parentOperationId);

-- 3. Materialized Expense Projections
CREATE TABLE IF NOT EXISTS expense_projections (
    groupId TEXT NOT NULL,
    expenseId TEXT NOT NULL,
    originOperationId TEXT NOT NULL,
    currentRevisionOperationId TEXT NOT NULL,
    authorPublicKey TEXT NOT NULL,
    logicalClock INTEGER NOT NULL,
    amountMinor INTEGER NOT NULL,
    currency TEXT NOT NULL,
    paidBy TEXT NOT NULL,
    payersJson TEXT NOT NULL,
    splitsJson TEXT NOT NULL,
    description TEXT NOT NULL,
    category TEXT NOT NULL,
    note TEXT NOT NULL,
    splitType TEXT NOT NULL,
    expenseDateMillis INTEGER NOT NULL,
    isVoided INTEGER NOT NULL,
    voidedByOperationId TEXT,
    voidReason TEXT,
    PRIMARY KEY (groupId, expenseId)
);
CREATE INDEX IF NOT EXISTS index_expense_projections_groupId_expenseDateMillis ON expense_projections (groupId, expenseDateMillis);
CREATE INDEX IF NOT EXISTS index_expense_projections_groupId_isVoided ON expense_projections (groupId, isVoided);

-- 4. Materialized Balance Projections
CREATE TABLE IF NOT EXISTS balance_projections (
    groupId TEXT NOT NULL,
    memberPublicKey TEXT NOT NULL,
    balanceMinor INTEGER NOT NULL,
    currency TEXT NOT NULL,
    stateDigest TEXT NOT NULL,
    updatedAtMillis INTEGER NOT NULL,
    PRIMARY KEY (groupId, memberPublicKey)
);
CREATE INDEX IF NOT EXISTS index_balance_projections_groupId ON balance_projections (groupId);

-- 5. Outbox Operations Table
CREATE TABLE IF NOT EXISTS outbox_operations (
    operationId TEXT NOT NULL PRIMARY KEY,
    groupId TEXT NOT NULL,
    nostrEventId TEXT NOT NULL,
    signedEventJson TEXT NOT NULL,
    keyEpoch INTEGER NOT NULL,
    attemptCount INTEGER NOT NULL,
    nextAttemptAtMillis INTEGER NOT NULL,
    status TEXT NOT NULL,
    createdAtMillis INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_outbox_operations_status_nextAttemptAtMillis ON outbox_operations (status, nextAttemptAtMillis);
CREATE INDEX IF NOT EXISTS index_outbox_operations_groupId ON outbox_operations (groupId);
CREATE UNIQUE INDEX IF NOT EXISTS index_outbox_operations_nostrEventId ON outbox_operations (nostrEventId);

-- 6. Relay Deliveries Table
CREATE TABLE IF NOT EXISTS relay_deliveries (
    operationId TEXT NOT NULL,
    relayUrl TEXT NOT NULL,
    nostrEventId TEXT NOT NULL,
    accepted INTEGER NOT NULL,
    relayMessage TEXT,
    ackedAtMillis INTEGER NOT NULL,
    PRIMARY KEY (operationId, relayUrl)
);
CREATE INDEX IF NOT EXISTS index_relay_deliveries_nostrEventId ON relay_deliveries (nostrEventId);
CREATE INDEX IF NOT EXISTS index_relay_deliveries_operationId_accepted ON relay_deliveries (operationId, accepted);

-- 7. Group Protocol Metadata Table
CREATE TABLE IF NOT EXISTS group_protocol_metadata (
    groupId TEXT NOT NULL PRIMARY KEY,
    protocolVersion INTEGER NOT NULL,
    genesisOperationId TEXT,
    currentKeyEpoch INTEGER NOT NULL,
    latestStateDigest TEXT NOT NULL,
    maxLogicalClock INTEGER NOT NULL,
    headOperationIdsJson TEXT NOT NULL,
    v1MigrationStatus TEXT NOT NULL,
    v1MigratedTipHash TEXT,
    updatedAtMillis INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_group_protocol_metadata_protocolVersion ON group_protocol_metadata (protocolVersion);
```

---

## 3. Upgrading Legacy V1 Groups via Checkpointing

When migrating a legacy V1 group to V2 via `V1ToV2MigrationManager.migrateGroupToV2(groupId)`:
1. **Verification:** The existing linear block chain is structurally validated with `BlockchainEngine.verifyStructuralChain(blocks)`.
2. **Extraction:** All members, active expenses, voided IDs, settlements, and ghost aliases are extracted.
3. **Conversion:** Double amounts are deterministically converted to `Long` integer minor units (`MoneyMath.toMinorUnits`).
4. **Signature Authenticity:** Instead of forging historical signatures for original authors (which is cryptographically impossible and insecure), a new `LegacyV1Checkpoint` operation payload is created and signed by the migrating peer (the creator or admin), attesting to the verified V1 tip hash.
5. **Ingestion & Reconciliation:** The checkpoint operation is stored in `signed_operations`, registered in `group_protocol_metadata`, reconciled into `expense_projections` and `balance_projections`, and broadcasted to connected Nostr relays.
6. **Continuation:** Subsequent expenses, edits, voids, and settlements build directly on top of the checkpoint in the DAG.
