# PeerSplit Data Safety worksheet

Reviewed October 18, 2026. This is a release-preparation worksheet, not a completed Console declaration. Reconcile it with the final AAB, exact dependency versions, network behavior and current form definitions.

## Data-flow inventory

| Flow | Data and recipients | Purpose and controls | Declaration work |
| --- | --- | --- | --- |
| Local profile and ledger | Display name, optional phone/UPI address, local signing identity, members, financial records and notes on device | App functionality; optional fields and local removal | Local-only processing is outside collection when nothing leaves the device. Do not assume fields remain local once shared in a group. |
| Encrypted group synchronization | Group financial/member records encrypted to group recipients through independent public Nostr relays | Group synchronization; network access needed | Assess the E2EE exclusion only for payloads readable solely by intended senders/recipients, not visible metadata. Group members hold decryption keys. |
| Relay connection and envelope | IP address, public identifiers, event IDs/timestamps, routing tags and ciphertext visible to relay operators | Synchronization, routing and replay | Assess User IDs and other relevant categories; do not treat pseudonymous identifiers as anonymous. Do not claim ephemeral processing or assume independent relay operators are contracted service providers. |
| Bundled ML Kit OCR | Technical device/app information, installation IDs, performance/error events, API configuration and input/output sizes sent to Google | Diagnostics and usage analytics for SDK; OCR feature uses selected images locally | Map to Diagnostics, Device or other IDs, and App interactions where the form definitions apply. Verify exact SDK guidance. No SDK opt-out has been established by this review; do not mark collection optional merely because receipt recognition is optional. |
| Selected contact/image/file | Chosen contact details, receipt/QR image or imported statement | User-requested local import/recognition | Local processing alone is not collection. Resulting shared ledger fields must be considered separately. No broad address-book access or camera permission in current app manifest. |
| Exports and Android sharing | CSV financial/member information, reminders, QR codes, invitations and encrypted backups to chosen apps/providers | Explicit user action | Consider the specific user-initiated sharing exception only where its conditions hold. CSV and reminders can disclose plaintext. An encrypted backup can contain private identity and group keys. |
| UPI handoff | Recipient UPI address/name, amount and payment note passed to a selected payment app or QR recipient | User-requested payment handoff | Consider financial/payment information and user-initiated sharing rules. PeerSplit neither holds funds nor independently verifies the payment. |
| Support enquiries | Support email content and attachments the user sends to peerloomlabs@gmail.com, handled through Gmail | Support and privacy enquiries | The developer supplied this Gmail address for privacy/support. Define mailbox access and retention before launch; review support disclosures against the actual process. |

## Decisions before completing the form

- Do not reuse “No data collected” or “No data shared” as blanket defaults. Include third-party SDK behavior, even when Peerloom Labs does not receive the data itself.
- Evaluate collection and sharing separately, with categories, purposes, required/optional status, and any valid exclusions for each flow. Public keys can identify a persistent user.
- Consider Personal info (including User IDs), Financial info, Contacts and Other user-generated content for actual shared fields. Their presence locally does not itself settle collection under Play's definition.
- TLS protects configured wss relay connections; Google documents HTTPS for ML Kit metrics. Verify all actual endpoints before answering the all-data encryption-in-transit question. Plaintext user exports are not encrypted at rest by PeerSplit.
- Do not claim automatic global deletion. Local deletion does not reach peers, relay storage, exports or independent SDK retention. Review the deletion-request answer against a real monitored support process.
- No email/password registration exists, but a persistent local cryptographic identity does. Review Play's account-creation/deletion scope against the final onboarding flow; absence of a central login does not justify an unexamined “not applicable.”
- No ads, billing or advertising-ID access was found in the current reviewed app. This does not imply zero SDK telemetry.
- Do not claim an independent security review badge from local code reviews or unit tests.

## Evidence to retain

Record the release version/hash, resolved dependency inventory, endpoint/traffic review, SDK provider disclosures, selected form answers and reasons, privacy-policy URL, verified support contact and a screenshot of each final declaration. Recheck all active tracks when behavior changes.

Sources: [Play Data Safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en), [ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure), [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en). Google's ML Kit disclosure describes latest SDKs; confirm its applicability to the exact bundled version rather than assuming this worksheet is a version-specific vendor guarantee.

## Console preparation on October 19, 2026

Saved for review: collected User IDs (also shared with public relays, app functionality), Diagnostics, App interactions, and Device or other IDs (SDK analytics). These were treated as non-ephemeral and required because no collection opt-out is implemented. Configured relay connections use WSS and the SDK provider documents HTTPS telemetry. Encrypted group payloads were assessed under Play's E2EE exclusion; explicit exports/payment handoffs under its user-initiated transfer exclusion. These exclusions do not extend to visible relay identifiers or SDK metrics. Recheck if any new plaintext metadata or endpoints are added.

No hosted account creation or external-account sign-in was declared: the identity is generated and managed locally, and Google's account-deletion FAQ excludes accounts created and operated offline. Persistent public signing identifiers remain disclosed as User IDs. No global deletion badge or independent-security/UPI accreditation badge was claimed. Source: [account deletion FAQ](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en). This records the saved answers, not Google approval.
