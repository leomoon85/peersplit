# Google Play Store Listing & Publishing Guide for PeerSplit

This guide provides all the copy, legal text, data safety answers, and technical checklist items required to publish **PeerSplit** to the Google Play Store Console.

---

## 1. Store Listing Copy

### App Title
`PeerSplit: Serverless P2P Split` (30 characters max)

### Short Description (80 chars max)
`Split expenses with friends using serverless peer-to-peer blockchain technology.`

### Full Description (Up to 4,000 characters)
```
Split bills and shared expenses with zero server dependencies, zero subscriptions, and 100% financial privacy.

PeerSplit is the revolutionary expense-sharing app designed for roommates, trips, dinners, and group outings. Unlike traditional bill-splitting apps that store your financial data on centralized company servers, PeerSplit uses group-scoped micro-blockchain technology to keep your records private and synchronized directly between you and your friends' phones.

WHY PEERSPLIT?

⛓️ SERVERLESS MICRO-BLOCKCHAINS
Every expense, settlement, and group update is recorded as an immutable block in your group's cryptographic ledger. Blocks are chained with SHA-256 hashes and signed using Ed25519 digital signatures.

🔒 ZERO CENTRAL STORAGE & TOTAL PRIVACY
No user accounts, no passwords, and no central database. Your expense notes and numbers never sit unencrypted on any company server. Group payloads are end-to-end encrypted (E2EE) with 256-bit AES-GCM keys.

🌐 REAL-TIME DECENTRALIZED SYNC
PeerSplit synchronizes peer-to-peer over open, public Nostr relays. Relays act solely as blind message conduits—they cannot inspect your transactions, participants, or amounts.

⚡ SMART DEBT SIMPLIFICATION
Tangled web of who owes whom? PeerSplit’s greedy min-cash-flow algorithm automatically simplifies cross-debts into the fewest possible payments.

🔍 IN-APP BLOCKCHAIN EXPLORER
Verify your ledger with a single tap. The built-in Blockchain Audit screen lets you inspect every block, verify cryptographic proofs, and ensure no transaction has ever been tampered with.

KEY FEATURES:
• Multiple currencies supported (USD, EUR, GBP, INR, CAD, AUD, JPY)
• Equal and custom bill splits
• One-tap debt settlement tracking
• Beautiful Material 3 UI with automatic Dark and Light themes
• Offline support: record expenses anywhere and sync automatically when connected
• Easy group onboarding via invite links and QR codes

Say goodbye to server outages, monthly fees, and privacy leaks. Split fairly, split securely with PeerSplit.
```

---

## 2. Google Play Console Data Safety Declaration

Google Play requires a comprehensive Data Safety form. Because PeerSplit is completely serverless, your declaration is simple and privacy-centric:

| Question | Answer | Rationale |
| :--- | :--- | :--- |
| **Does your app collect or share any user data?** | **No** | All ledger data is stored locally in SQLite Room DB on the user's phone. Transmitted packets are ephemeral, encrypted peer messages. |
| **Is all user data encrypted in transit?** | **Yes** | WebSockets use TLS (`wss://`) and payloads are encrypted with AES-256-GCM. |
| **Do you provide a way for users to request data deletion?** | **Yes** | Deleting a group or clearing app storage permanently removes all local cryptographic keys and databases. |
| **Does this app use advertising IDs?** | **No** | No ads, no tracking SDKs. |

---

## 3. Privacy Policy (Required by Google Play)

Host this text on a public GitHub Page or website and submit the URL in Google Play Console -> **App Content** -> **Privacy Policy**:

```markdown
# Privacy Policy for PeerSplit

Last updated: October 2026

PeerSplit ("we", "our", or "the app") is committed to protecting your privacy through a decentralized, serverless architecture.

1. Information We Collect
PeerSplit does not collect, log, or store any personal information, emails, phone numbers, or passwords on any centralized servers. 

2. How Your Data is Stored
All financial ledgers, expense entries, contact nicknames, and cryptographic private keys are stored locally on your device within the app's sandboxed storage.

3. Peer-to-Peer Communication & Encryption
When syncing with group members, expense blocks are end-to-end encrypted with an AES-256-GCM symmetric key shared only among group members. Encrypted messages are routed through public decentralized relays. Relay operators cannot read or decrypt the contents of your transactions.

4. Third-Party Analytics and Advertising
PeerSplit does not integrate third-party analytics (e.g., Firebase Analytics), tracking beacons, or advertisement networks.

5. Data Retention & Deletion
Because all data resides exclusively on your device, deleting the app or clearing the app storage permanently deletes all local records and cryptographic keys.

6. Contact
For open-source inquiries or security audit reports, contact us via our project repository.
```

---

## 4. App Category & Target Audience

- **Category**: `Finance` (or `Productivity`)
- **Tags**: `Expense Tracker`, `Bill Splitting`, `Personal Finance`, `Budgeting`, `Peer to Peer`
- **Target Age**: 18 and older (or 13+)
- **Ads**: Declare **"No, my app does not contain ads"**.

---

## 5. Technical Pre-Launch Checklist

- [x] Target SDK set to **35** (Android 15 compliance)
- [x] Minimum SDK set to **26** (Android 8.0+)
- [x] 64-bit architecture supported (Pure Kotlin/Java + Android Keystore)
- [x] Proguard/R8 configured for release minification
- [x] App Bundle (`.aab`) configured for packaging
- [x] Unit test suite passing (`./gradlew test`)
- [ ] Prepare Google Play Store Assets:
  - App Icon (512 x 512 px PNG)
  - Feature Graphic (1024 x 500 px JPEG/PNG)
  - At least 4 phone screenshots (1080 x 1920 px or 1080 x 2400 px)
