# PeerSplit

PeerSplit by **Peerloom Labs** is an Android app for shared expenses, group balances and settlement records. It is currently free, with no ads, in-app purchases or subscriptions.

## How it works

Records live in a local Room database. The V2 protocol uses signed operations, causal history and deterministic projections to calculate group state. Group payloads are encrypted with group-scoped keys and synchronized through public Nostr relays. There is no Peerloom Labs central expense database; synchronization still depends on network services and relay availability.

Public relays can retain encrypted events and see connection and envelope metadata. Group members can read shared records and retain copies. Deleting local data does not erase distributed history. Local storage uses Android's sandbox; whole-database encryption at rest is not claimed.

Features include equal/custom splits, multiple payers, group balance tracking, debt simplification, ledger inspection, CSV import/export, password-encrypted backups, selected-image receipt OCR, and INR UPI app/QR handoffs. Debt simplification uses a greedy algorithm and does not guarantee the mathematically fewest payments. A recorded settlement is not bank confirmation.

OCR runs on-device with ML Kit, which also collects SDK diagnostics and usage metrics. See the [privacy policy](PRIVACY_POLICY.md) before sharing sensitive information. No email/password registration is required; the app creates a persistent local cryptographic identity.

## Development

Use Android Studio, JDK 17 and the SDK versions specified in the Gradle configuration. The current app has minimum SDK 26, compile/target SDK 36, package `com.peersplit.app` and version 1.0.0. Check current Google Play requirements before submission; this configuration alone is not a release approval.

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :shared:testDebugUnitTest :shared:desktopTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:bundleRelease
```

Configure release signing privately using the project's Gradle signing configuration. Keep keystores, signing passwords and local credential files out of version control. A successful release APK build does not verify the final Play app bundle or device compatibility.

## Release documentation

- [Privacy policy](PRIVACY_POLICY.md): publicly accessible on GitHub and available offline in the app.
- [Store listing copy](STORE_LISTING.md).
- [Play publishing guide](PLAYSTORE_LISTING_GUIDE.md).
- [Data Safety worksheet](docs/DATA_SAFETY.md).
- [Release checklist](docs/RELEASE_CHECKLIST.md).
- [Usage and data recovery notes](docs/USAGE_AND_RECOVERY.md).
- [V2 protocol specification](docs/V2_PROTOCOL_SPEC.md) and [known limitations](docs/V2_KNOWN_LIMITATIONS.md).

The in-app offline policy is generated from `PRIVACY_POLICY.md`. After changing the policy, run `python scripts/sync_privacy_policy.py`; use `--check` to detect a stale packaged copy. Privacy and support contact: [leo.moon85@gmail.com](mailto:leo.moon85@gmail.com).

On October 18, 2026, the reviewed implementation passed 83 JVM tests, debug and minified release APK builds, and lint with zero errors and nine warnings; some custom navigation lint checks were skipped. Physical-device, accessibility, payment-handoff and multi-device acceptance remain necessary. These results are a dated verification snapshot, not production certification.

Repository visibility and an open-source license must be established before describing the app as open source. No LICENSE file was found in this review.
