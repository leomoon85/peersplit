# PeerSplit Google Play publishing guide

Updated: October 18, 2026. Developer display name: **Peerloom Labs**.

This guide supports submission; it does not certify policy compliance or production readiness.

1. Use [STORE_LISTING.md](STORE_LISTING.md) for consistent listing copy. The app is currently free, contains no ads and has no in-app purchases or subscriptions.
2. Publish [PRIVACY_POLICY.md](PRIVACY_POLICY.md) as an accessible public HTML policy and add its URL in Play Console. The in-app policy is available under Profile > Privacy policy. Verify both on a fresh installation. Privacy/support contact: `leo.moon85@gmail.com`.
3. Complete the form using [docs/DATA_SAFETY.md](docs/DATA_SAFETY.md). ML Kit telemetry and visible relay metadata prevent reusing the previous unsupported blanket “No data collected” answer. Verify exact SDK versions and every active release track.
4. Confirm the developer's actual legal/account details in Play Console and enter `leo.moon85@gmail.com` as the support email. A brand name alone is not a registered legal entity. Do not reuse the former dev@peersplit.app address.
5. Select an accurate category (Finance is a reasonable starting point), actual target audience, content rating and countries. Do not treat a suggested 18+ audience as an already approved product decision. If targeting children, review the applicable Families requirements before doing so.
6. Complete financial-features declarations against the current questions: describe expense management and external UPI handoffs accurately. Do not describe PeerSplit as a bank, lender, money custodian or verified payment processor. Do not claim an NPCI/UPI accreditation badge without approval.
7. Check current target API requirements and account-specific deadlines before submission. The repository currently targets API 35; remove any old checklist statement that this alone proves current Play compliance.
8. Build and validate the signed release AAB. Check native-library 16 KB page-size compatibility and real-device behavior; the bundled OCR dependencies mean that “pure Kotlin, no native compatibility checks needed” is not justified.
9. Complete [the release checklist](docs/RELEASE_CHECKLIST.md), internal testing and any account-required closed testing. Newer personal accounts may need 12 testers continuously opted in for 14 days before applying for production access; check your account's eligibility requirements.
10. Upload current assets: 512 x 512 app icon, 1024 x 500 feature graphic, and accurate phone screenshots. Recheck the asset requirements in Console. Avoid claims that the app is already audited, approved or universally secure.

## Primary references

- [User Data and privacy policy requirements](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
- [Data Safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- [ML Kit SDK disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
- [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- [Personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
- [Android 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)

Requirements can change. Recheck the Console's current instructions at submission time.
