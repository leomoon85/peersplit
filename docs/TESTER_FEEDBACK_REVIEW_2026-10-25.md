# Tester feedback and external-payment review

Reviewed October 25, 2026 against Antigravity commit e96fca4 plus the current local changes. The supplied CSV contains 15 installed testers, 10 feedback responses and 5 pending responses. Individual tester names and raw feedback are not reproduced here.

## Findings and changes

| Area | Review result |
| --- | --- |
| International phone numbers | Antigravity integrated complete digit preservation, extension handling and tests. Existing numbers truncated by an older build must be selected again or re-entered; missing digits cannot be reconstructed. |
| Member matching | Complete normalized numbers are compared rather than ten-digit suffixes. Members should consistently include country codes; this change does not rewrite old signed records or repair previously truncated records. |
| First-run explanation | Name-only setup, reusable guide, skip action and saved introduction state are integrated. Phone and UPI fields remain optional in Profile. |
| Guide privacy claims | Corrected the new Zero Servers / 100% Local claims to explain group recipients, relay metadata and ML Kit diagnostics. |
| Guide on compact screens | Reduced the fixed vertical reserve when the available height is small, including with the keyboard open. Physical-device verification remains pending. |
| Currency discovery | The selector exposes supported currencies including EUR and AED. This is one currency per group, not currency conversion. |
| International UPI suggestions | Fixed an additional settlement-screen path that suggested a UPI address from an unrestricted international number; suggestions now use the Indian-mobile helper. Direct UPI remains an explicit INR-only action. |
| External payment apps | Added a theme-aware Pay externally card, supported-installed-app dropdown, explicit Copy amount action, loading/error/empty states and a manual fallback. |
| Dense dashboard | Antigravity integrated the smaller SplitPoints entry. Further redesign of group creation is not represented as completed. |
| Responsiveness | App discovery runs off the main thread and cancellation is preserved. General performance complaints still need device traces and reproduction steps. |
| Languages | English-only availability is stated accurately. Full translation and Arabic RTL review remain backlog items, not completed fixes. |

## Security and data handling

The new picker queries eight named packages and never requests QUERY_ALL_PACKAGES. It keeps the detected list in transient Compose memory and adds no network endpoint, telemetry, persisted preference or payment SDK. The manifest's existing permissions remain INTERNET and ACCESS_NETWORK_STATE, in addition to permissions contributed by existing dependencies.

The launcher accepts only a catalog package and verifies that the resolved component belongs to it. It constructs a fresh ACTION_MAIN/CATEGORY_LAUNCHER intent with no URI or extras. This action passes no amount, recipient or currency. Disappeared/inaccessible activities return failure rather than recording payment. Detection handles absent packages and blocked visibility. Copy amount is a separate user action using the sensitive-clipboard helper.

There is no activity-result callback and no connection from this picker to onRecordSettlement. Returning from a wallet therefore does not create a settlement. Existing UPI deep links deliberately pass payment details and remain separate from this new action; the privacy policy distinguishes them.

A wallet appearing in the dropdown is not proof that it supports peer transfers or the group's currency. Unlisted apps can still be opened manually. The supported catalog is finite and will need maintenance.

## Automated verification

- 92 JVM tests passed: 84 app tests, 4 shared Android tests and 4 shared desktop tests.
- Debug APK build passed.
- Lint completed with zero errors, 23 warnings and 2 hints. Warnings include existing dependency, icon, backup-rule and KTX suggestions.
- Signed, minified release AAB build passed. Jarsigner reported jar verified (with self-signed-certificate / archive-order warnings in its log). ZIP integrity passed; packaged privacy policy matches source after newline normalization.
- Release SHA256: `a98c6a5ad641da3604cf8f541749f22c134b05eb0b96b9fdb3dde4bfa0c2731e`.
- Packaged-policy consistency and manifest permission checks are part of the release review.

The new catalog tests exercise no-app behavior, blocked package visibility, filtering and rejected unknown targets. They do not simulate the real Android PackageManager or external wallet UI. These checks are scoped regression evidence, not a guarantee of zero bugs or an independent security certification.

## Required device acceptance before rollout

1. On light/dark themes, a narrow phone, landscape and large text, inspect the card and dropdown for clipped text, contrast and reachable actions.
2. Open the guide with the keyboard visible; verify Get started/Later remain reachable and skipping does not alter payment/profile fields.
3. Test zero supported apps, one supported app and several supported apps; uninstall or disable one after opening the menu and verify graceful failure.
4. Select a real app and verify it opens without prefilled payment details, then cancel/return and confirm no settlement has appeared.
5. Copy a valid amount explicitly and verify the clipboard contains the numeric amount only. Invalid amounts must not be copied.
6. Verify manual settlement still works with no payment app installed and no network; separately verify INR UPI and non-INR flows.
7. Re-select a UAE contact and verify the full country code survives save/edit/reload and group matching.

No connected device or configured emulator was found during the local environment check, so these device checks have not been claimed as completed. This change has not been uploaded to Play Console by this review.
