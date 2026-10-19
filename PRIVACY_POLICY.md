# Privacy Policy for PeerSplit

Last updated: October 19, 2026

Peerloom Labs develops PeerSplit (`com.peersplit.app`), an Android application for recording and sharing group expenses. This policy describes the current app. PeerSplit is currently free, with no advertising, in-app purchases, or subscriptions.

## Information used by the app

PeerSplit creates a local cryptographic identity instead of requiring email/password registration. This identity includes persistent public identifiers and a private signing key. Your local profile can include a display name, optional phone number, and UPI address.

The app stores group names, member details, expenses, descriptions, notes, amounts, splits, balances, settlements, preferences, and signed operation history on your device. It uses these records to calculate balances, display history, synchronize groups, and provide exports and backups. Adding another person's information makes it available to the relevant group; only include information you are entitled to share.

Peerloom Labs does not operate a central expense database for this app. This does not mean data never leaves your phone: group synchronization, SDK diagnostics, and actions you choose can transfer data as explained below.

## Group synchronization and public relays

Group operation payloads are encrypted with group-scoped AES-GCM keys before transmission through public Nostr relays. Group members with the necessary keys can decrypt shared records. Cryptographic signatures help verify authorship and detect changes; they do not establish that an expense or payment claim is truthful.

Relay operators can observe connection information such as your IP address, public signing identifiers, event identifiers, timestamps, routing tags and other envelope metadata, as well as encrypted payloads. This service is not anonymous. Configured relays include relay.damus.io, nos.lol, relay.primal.net, nostr.mom and relay.snort.social; the configured set may change with app updates.

These independently operated services may store and replay events. Their retention, availability and handling of connection logs are controlled by their operators. Relays are not merely temporary routers, and Peerloom Labs cannot guarantee removal of their copies. Removing a member or rotating a key cannot revoke information or keys already obtained by that member.

## Receipt recognition and SDK diagnostics

When you select a receipt image, text recognition is performed on the device using Google's bundled ML Kit text-recognition SDK. PeerSplit does not upload that image to a cloud OCR service for recognition.

ML Kit also collects technical information for diagnostics and usage analytics. Google's disclosure describes device and app information, installation identifiers, performance and error events, and recognition configuration and input/output size metadata. On-device recognition therefore does not mean the SDK sends no diagnostic data. Google documents HTTPS protection for this telemetry. See [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [Google's Privacy Policy](https://policies.google.com/privacy). SDK practices and retention are governed by Google; Peerloom Labs does not provide a deletion control for Google's independently handled diagnostics.

## Files, contacts and sharing you choose

Android's system pickers let you choose receipt or QR images, import files, save exports and backups, or select a contact. Contact selection accesses the contact information you choose, rather than uploading your address book. The current app does not request camera, broad storage, contacts, SMS or location permissions. Internet and network-state access support synchronization; Android background-work components may use system scheduling permissions.

CSV exports are not encrypted by PeerSplit. Exports, reminders, QR codes and invitations may reveal financial details, member information, payment addresses or group access information to the recipients you choose. Treat invitations and group keys as sensitive. Apps, messaging services and document/cloud providers you select handle their copies under their own policies.

## UPI payments

For INR payment handoffs, PeerSplit can open a compatible UPI app or generate a payment QR code containing recipient and payment details. The payment provider receives those details. PeerSplit does not hold or transfer funds, request your UPI PIN or OTP, or independently verify that a bank transfer succeeded. Recording a settlement in PeerSplit is a ledger entry, not proof of payment. Confirm recipient details and payment status in your payment app.

## Storage, security and backups

Local records are stored in the app's Android sandbox. This is not a claim that the entire local database is encrypted at rest. Network relay connections use secure WebSockets, and group payloads use application-level encryption. No security system can guarantee protection against a compromised device, leaked keys or an authorized group member copying records.

Android automatic app backup is disabled in the current manifest. You can explicitly create password-encrypted PeerSplit backups. These can contain your private signing identity, profile, group keys and group history. Anyone with the backup and password may recover that identity and its data. Keep them secure; Peerloom Labs cannot recover a lost backup password or a lost identity. Temporary encrypted staging files may remain in app-private cache until removed by the app or Android.

## Retention, deletion and your choices

Local information remains until removed through the app, cleared in Android settings, or removed when uninstalling. To remove this installation's data and local identity, use Android Settings > Apps > PeerSplit > Storage > Clear storage, or uninstall. Delete exported files, backups and shared copies separately from their destinations. Restoring an old backup can restore previously removed records.

Deleting a local group does not erase other members' devices or public relay copies. Edits and voids update the ledger's effective state while retaining signed history. An earlier expense can therefore remain in historical records. Peerloom Labs cannot perform a global deletion of this distributed history or recover copies held by others. Consider these limits before including sensitive personal details in shared groups.

You can choose not to use OCR, contact selection, exports, backups or UPI handoffs. Stopping network access prevents relay synchronization. Contact Peerloom Labs about information you submit directly for support; avoid including private keys, backup passwords, receipts or other people's personal information in a public issue.

## Children and changes

PeerSplit is intended for people aged 13 and above, with initial availability planned for India. The app does not verify a user's age. Peerloom Labs does not knowingly solicit children's personal information for marketing. Avoid entering children's personal information in shared group records. For concerns about a child's information submitted directly to Peerloom Labs, contact the privacy email below.

This policy may change when the app or its services change. Updates will be identified by the date above and published with the app's policy materials.

## Privacy contact

Developer: **Peerloom Labs**.

For privacy questions, support, or requests about information you have sent directly to Peerloom Labs, email [peerloomlabs@gmail.com](mailto:peerloomlabs@gmail.com). Do not send private keys, backup passwords or UPI credentials. We cannot erase records held independently by group members or relay operators.
