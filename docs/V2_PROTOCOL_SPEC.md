# PeerSplit V2 Protocol Specification

## 1. Protocol Overview

- **Protocol Name:** PeerSplit Signed-Operation DAG Protocol
- **Protocol Version:** `2`
- **Wire Serialization:** JSON + AES-256-GCM symmetric encryption
- **Canonical Envelope Encoding:** Big-Endian binary canonical encoding with UTF-8 NFC normalization
- **Signatures:** Ed25519 (RFC 8032)
- **Hashing & Digest:** SHA-256

---

## 2. Canonical Encoding Rules

To eliminate signature non-determinism across platforms, operating systems, and JSON serialization engines, unsigned operations are converted into a strict, byte-exact binary format via `OperationCanonicalizer`:

1. **Protocol Version:** 4 bytes, big-endian int (`0x00, 0x00, 0x00, 0x02`).
2. **Group ID:** 4-byte length prefix + UTF-8 NFC-normalized bytes.
3. **Author Public Key:** 64-hex lowercase ASCII string (32 bytes).
4. **Parents List:**
   - Deterministically sorted lexicographically.
   - Distinct (duplicates cause validation failure).
   - Maximum 32 parents.
   - Encoded as 4-byte count + for each parent: 4-byte length + hex ASCII bytes.
5. **Logical Clock:** 8 bytes, big-endian long.
6. **Key Epoch:** 4 bytes, big-endian int.
7. **Created At Millis:** 8 bytes, big-endian long.
8. **Payload Tag & Fields:**
   - 1-byte payload discriminator tag (`0x01` = Genesis, `0x02` = ExpenseCreated, `0x03` = ExpenseUpdated, `0x04` = ExpenseVoided, `0x05` = SettlementRecorded, `0x06` = MemberAdded, `0x07` = MemberRemoved, `0x08` = LegacyV1Checkpoint).
   - All maps (`splits`, `payers`, `ghostAliases`) are sorted by key in ascending order.
   - All monetary amounts are encoded as 8-byte big-endian `Long` minor units.

---

## 3. Cryptographic Domain Separation

Domain tags prevent cross-context replay attacks:

| Domain Tag | Purpose |
|------------|---------|
| `PeerSplit/V2/Operation` | Operation ID content hashing: `SHA-256(Tag || 0x00 || canonicalUnsignedBytes)` |
| `PeerSplit/V2/Signature` | Signing digest: `SHA-256(Tag || 0x00 || operationIdBytes || canonicalUnsignedBytes)` |
| `PeerSplit/V2/StateDigest`| Ledger state digest: `SHA-256(Tag || 0x00 || sortedAcceptedOpIds || sortedBalances)` |
| `PeerSplit/V2/KeyEpoch`   | Key derivation and epoch advancement tag |

---

## 4. Operation Envelope Schema

```kotlin
@Serializable
data class SignedOperation(
    val protocolVersion: Int,
    val groupId: String,
    val authorPublicKey: String,
    val parents: List<String>,
    val logicalClock: Long,
    val payload: OperationPayload,
    val operationId: String,
    val signature: String,
    val keyEpoch: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis()
)
```

---

## 5. Wire Transport (Nostr Relay Mapping)

V2 operations are packaged in `WireOperationEnvelope`, encrypted using AES-GCM with the group's 256-bit symmetric key, and published as Nostr events (`kind: 29999`):

### Nostr Event Tags
```json
[
  ["t", "peersplit_<groupId>"],
  ["t", "peersplit_v2"],
  ["g", "<groupId>"],
  ["op", "<operationId>"],
  ["v", "2"],
  ["epoch", "<keyEpoch>"]
]
```

### Encrypted Content
`EncryptionUtil.encryptString(json.encodeToString(wireEnvelope), groupKey)`
Where `WireOperationEnvelope`:
```json
{
  "version": 2,
  "groupId": "...",
  "operationId": "...",
  "keyEpoch": 0,
  "serializedOperationJson": "{...}"
}
```

---

## 6. Financial Integrity Invariants

1. **Integer Representation:** All amounts are represented in 64-bit signed integer minor units (`Long`). ₹1.00 is stored as `100L` paise.
2. **Positive Values:** All expense amounts and settlement amounts must be `> 0L`. Zero and negative amounts are strictly rejected.
3. **Split Conservation:** For every expense, `sum(splits.values) == amountMinor`.
4. **Multi-Payer Conservation:** When multiple payers are specified, `sum(payers.values) == amountMinor`.
5. **No Mixed Currencies:** Operations specifying a currency differing from `groupEntity.currency` are rejected with `CURRENCY_MISMATCH`.
6. **Zero-Sum Ledger:** For all accepted operations across a group, `sum(netBalances.values) == 0L`.
