# Privacy Policy for PeerSplit

**Last updated:** October 17, 2026

PeerSplit ("we", "our", or "the app") is a serverless, peer-to-peer expense sharing application built on local cryptographic micro-blockchains and decentralized relays. We are strongly committed to user privacy and data sovereignty.

---

## 1. Zero Centralized Data Collection
PeerSplit does **not** operate centralized databases, cloud accounts, or tracking backends:
- **No User Accounts:** You do not need to register with an email address, phone number, password, or third-party OAuth provider.
- **No Analytics / Telemetry:** We do not embed third-party analytics trackers, crash beacons, or advertising SDKs.
- **No Financial Tracking:** We never see, harvest, or monetize your expenses, group names, balances, or debts.

---

## 2. On-Device Storage & Cryptographic Security
- **Local Ledger:** All group data, expense blocks, and settlement calculations are stored locally on your device in a secure SQLite database using Android's sandboxed storage.
- **Cryptographic Signatures:** Every transaction block is signed using an Ed25519 private key generated and stored securely on your device.
- **End-to-End Encryption (E2EE):** When sharing transactions, payloads are encrypted using 256-bit AES-GCM with group-scoped keys. Relays and third parties cannot decrypt your expense descriptions, amounts, or participant identities.

---

## 3. Decentralized Peer-to-Peer Relays
- Group members synchronize data across open, public Nostr relays (`wss://relay.damus.io`, `wss://nos.lol`, etc.).
- Relays act exclusively as ephemeral message routers. Because all payloads are encrypted before transmission, relay operators cannot read your expense information.

---

## 4. Device Permissions
PeerSplit requests minimal, optional permissions strictly to provide core on-device utilities:
- **Camera (Optional):** Used exclusively for scanning peer QR codes to join groups, sync ledgers offline, or scan receipts with on-device Optical Character Recognition (OCR). Camera feeds are processed entirely in memory on your device; images or video frames are never transmitted anywhere.
- **Photos / Storage / File Sharing (Optional):** Used when you explicitly choose to export group statements (CSV format) or share payment reminder QR images via Android's native share sheet.

---

## 5. Third-Party Payment Applications (UPI)
For users settling debts via UPI (Unified Payments Interface), PeerSplit simply generates standard UPI deep-link intents (e.g., `upi://pay?...`) or QR codes. PeerSplit is not a payment gateway, does not process money, and does not hold bank account credentials or PINs.

---

## 6. Children's Privacy
PeerSplit does not knowingly collect or solicit any personal information from children under the age of 13.

---

## 7. Open Source & Transparency
PeerSplit is an open source project. You can review and verify the complete codebase, cryptographic proofs, and lack of tracking libraries on GitHub:
[https://github.com/leomoon85/peersplit](https://github.com/leomoon85/peersplit)

---

## 8. Contact Us
If you have questions regarding this Privacy Policy or PeerSplit's decentralized architecture, please file an issue on our GitHub repository:
- **GitHub:** [https://github.com/leomoon85/peersplit/issues](https://github.com/leomoon85/peersplit/issues)
- **Developer Contact:** `dev@peersplit.app`
