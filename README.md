# PeerSplit ⛓️📱
> **Serverless Peer-to-Peer Expense Sharing Android App**  
> *A Splitwise alternative built on group-scoped micro-blockchains and decentralized relays.*

PeerSplit eliminates the need for proprietary centralized database servers, monthly subscription fees, and server maintenance costs. All expense records are structured as **cryptographically signed, append-only micro-blockchains** stored locally on users' smartphones and synchronized peer-to-peer over decentralized relays.

---

## 🌟 Key Features

- **No Central Server or Database**: No user credentials, financial transactions, or group records are ever uploaded to any proprietary server.
- **Micro-Blockchain Ledger per Group**: Each group maintains its own append-only hash chain. Each expense, settlement, or member addition is an immutable block secured with `SHA-256` and `Ed25519` digital signatures.
- **Tamper-Evident & Cryptographic Proof**: Any offline tampering with local database records breaks the cryptographic hash pointer (`prevHash`) and signature, alerting peers immediately.
- **Visual Blockchain Explorer**: In-app ledger audit screen allows users to inspect block hashes, creator public keys, and cryptographic proofs for 100% financial transparency.
- **Decentralized Relay Sync (Nostr Protocol)**: Devices synchronize in real-time over open, public Nostr relays (`wss://relay.damus.io`, `wss://nos.lol`, etc.). Relays only act as ephemeral routers.
- **End-to-End Encryption (E2EE)**: Payloads are encrypted with high-entropy 256-bit AES-GCM keys generated at group genesis. Relays and third parties cannot inspect expense titles, amounts, or member identities.
- **Min-Cash-Flow Debt Simplification**: Built-in greedy graph algorithm calculates the minimum number of settlements to square up balances across multi-person groups.
- **Modern Jetpack Compose UI**: Material 3 interface with dynamic color, light/dark themes, edge-to-edge rendering, and intuitive navigation.

---

## 🏗️ Architecture

```
PeerSplit Architecture
├── Cryptographic Layer (Ed25519, SHA-256, AES-256-GCM)
│   ├── KeyManager.kt       -> Hardware-bound device identity & signing
│   ├── HashUtil.kt         -> SHA-256 hashing & hex utilities
│   └── EncryptionUtil.kt   -> AES-256-GCM group payload encryption
│
├── Blockchain Engine (Append-Only Hash Chains & Consensus)
│   ├── Block.kt            -> Block data structure & hash calculation
│   ├── BlockPayload.kt     -> GroupGenesis, Expense, Settlement payloads
│   ├── BlockchainEngine.kt -> Block minting, chain validation, tamper detection
│   └── DebtSettlementEngine.kt -> Greedy Min-Cash-Flow graph reduction
│
├── Data & Local Storage (Room SQLite Database)
│   ├── AppDatabase.kt      -> Room database singleton
│   ├── Entities.kt         -> Groups, Members, Blocks entities
│   ├── Daos.kt             -> Reactive Room DAOs (Flows)
│   └── ExpenseRepository.kt-> Clean Architecture repository
│
├── P2P Decentralized Network Layer (Nostr Protocol)
│   ├── NostrEvent.kt       -> NIP-01 signed event creation & verification
│   ├── NostrClient.kt      -> OkHttp WebSocket connection per relay
│   ├── RelayPool.kt        -> Multi-relay manager with failover & deduplication
│   └── P2PSyncManager.kt   -> E2EE block broadcasting & incoming ingestion
│
└── Presentation Layer (Jetpack Compose + Material 3)
    ├── HomeScreen.kt       -> Overall balance & groups dashboard
    ├── GroupDetailScreen.kt-> Member balances & simplified debts
    ├── AddExpenseScreen.kt -> Equal & custom split calculation
    ├── SettleUpScreen.kt   -> Debt repayment block minting
    ├── CreateGroupScreen.kt-> Genesis block minting & invite generation
    ├── JoinGroupScreen.kt  -> QR code / invite link ingestion
    ├── LedgerAuditScreen.kt-> Interactive blockchain block explorer
    └── ProfileScreen.kt    -> Ed25519 pubkey & relay diagnostics
```

---

## 🚀 Getting Started

### Prerequisites
- [Android Studio Ladybug / Meerkat (or later)](https://developer.android.com/studio)
- JDK 17 or higher
- Android SDK 35 (Android 15)

### Setup & Run
1. Clone or open the project folder in Android Studio:
   ```bash
   cd l:/projectz/splitwise
   ```
2. Open Android Studio -> **File** -> **Open** -> select `splitwise`.
3. Allow Gradle to sync dependencies.
4. Run on an Android Emulator or connected physical device (Target SDK 35, Min SDK 26).

### Running Unit Tests
Execute the cryptographic, blockchain consensus, and debt simplification tests:
```bash
./gradlew test
```

---

## 📦 Building for Google Play Store

### 1. Generate a Release Signing Keystore
In PowerShell / terminal:
```bash
keytool -genkey -v -keystore peersplit-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias peersplit
```

### 2. Build the Android App Bundle (AAB)
```bash
./gradlew bundleRelease
```
The optimized bundle will be generated at:
`app/build/outputs/bundle/release/app-release.aab`

Refer to [PLAYSTORE_LISTING_GUIDE.md](file:///l:/projectz/splitwise/PLAYSTORE_LISTING_GUIDE.md) for Google Play Console metadata, Privacy Policy declarations, and Data Safety guidelines.
