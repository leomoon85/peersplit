# PeerSplit release checklist

Status snapshot: October 18, 2026. This checklist separates completed engineering evidence from remaining release work. An unchecked item has not been verified by this review.

## Documentation and privacy

- [x] Replace inaccurate zero-telemetry, ephemeral-relay, camera-permission and global-deletion claims in root release documents.
- [x] Prepare a consistent privacy policy, listing copy and Data Safety worksheet.
- [x] Add the developer-supplied privacy/support contact, `leo.moon85@gmail.com`, and remove contact placeholders.
- [x] Verify the GitHub policy URL opens without signing in (HTTP 200 on October 18). Worldwide availability still depends on GitHub access.
- [x] Add offline policy text under Profile > Privacy policy, generated from the root policy.
- [ ] Verify policy access, scrolling and dismissal on a clean-install device.
- [x] Regenerate the packaged policy after adding the supplied contact; `python scripts/sync_privacy_policy.py --check` passed. Repeat after any policy edit.
- [x] Save the policy URL in Peerloom Labs / PeerSplit Play Console.
- [ ] Check exact SDK disclosures and network flows; finalize the Data Safety form and retention/deletion support procedure.
- [x] Save ages 13+, no ads, government/health and expense-management financial declarations.
- [ ] Finish India track availability and content rating.
- [ ] Establish license terms before advertising the project as open source; do not infer a license from GitHub hosting.

## Engineering evidence already obtained

- [x] 83 JVM tests passed in the October 18 review run.
- [x] Debug APK and minified release APK builds passed.
- [x] Lint completed with zero errors and nine warnings. Some custom navigation checks were skipped; this is not complete navigation lint coverage.

These results apply to that reviewed source snapshot. Re-run appropriate checks after app changes; this documentation update alone does not require rerunning the full test suite.

## Release gates still to verify

- [x] Upgrade compile/target SDK to 36 with AGP 8.10.1 and checksum-pinned Gradle 8.11.1.
- [ ] Validate the final signed AAB, signing identity, increasing version code, upload certificate and Play App Signing setup.
- [ ] Check 16 KB native-library/page-size compatibility on the final bundle and supported architectures.
- [ ] Run installation, upgrade, offline/reconnection and multi-device relay tests on physical devices.
- [ ] Verify rotation/process recreation, large text, TalkBack, dark/light themes and navigation recovery on devices.
- [ ] Exercise backup export/restore, wrong-password/corrupt-file handling, local deletion and identity recovery expectations.
- [ ] Verify real UPI handoff cancellation/failure/success behavior; never equate a ledger entry with bank confirmation.
- [ ] Verify group invitations, key rotation/member removal, signed history and unavailable relay behavior across devices.
- [ ] Review existing icon/feature graphic/screenshots against the final UI and Console asset requirements.
- [ ] Complete account-required testing and production-access approval. Internal testing alone does not satisfy an applicable closed-testing requirement.
- [ ] Record final device evidence, known limitations, rollback/recovery plan and release approval.

See [publishing guide](../PLAYSTORE_LISTING_GUIDE.md) for policy references and [usage/recovery notes](USAGE_AND_RECOVERY.md) for user-facing limits. Historical audit reports remain dated evidence; this checklist does not assert every historical finding has been closed.
