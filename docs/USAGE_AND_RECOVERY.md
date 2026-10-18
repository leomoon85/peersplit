# PeerSplit usage and recovery notes

Updated October 18, 2026. These practical notes explain the current app; they are not a payment guarantee or a substitute for the [privacy policy](../PRIVACY_POLICY.md).

## Before sharing a group

Use group records with people you trust. Members with group keys can read and copy shared history. Invitations may carry access information: send them only to intended recipients. Include only personal information you have permission to share. Avoid putting bank credentials, identity documents or sensitive private information in expense notes.

## Check expenses and payments

OCR and imported statements can contain mistakes. Review totals, members, currency and split amounts before saving. Debt simplification offers suggested payments, not a guarantee of a mathematically minimal plan.

PeerSplit records financial claims and settlements; it does not certify them. For UPI handoffs, verify the recipient, amount and transfer status in the payment app. Never give PeerSplit support your UPI PIN, OTP, private signing key or backup password.

## Back up before changing devices

Create a password-encrypted PeerSplit backup and save it somewhere you control. It can include your signing identity and group decryption keys, so protect both the file and password. Peerloom Labs cannot reset this password or restore lost keys. Android automatic app backup is disabled in the current app configuration. Keep a working copy until you have checked a restoration on the new device.

CSV statements are readable exports, not identity backups. Sending a CSV does not transfer your private signing identity. Document providers or messaging apps may retain shared copies separately.

## Deleting local data

Delete a local group through the app when available. To remove all data and identity from this installation, clear PeerSplit's storage in Android settings or uninstall. Remove exported files and manual backups separately. Restoring a backup can restore deleted information.

Group deletion, clearing storage and uninstalling do not erase other members' copies or public relay history. Editing or voiding an expense does not remove its original signed historical operation. There is no global erase or account-recovery service.

## Connectivity and availability

You can record local changes while offline. Other devices receive them only when synchronization succeeds. Queued or requested synchronization does not mean every member has received the changes. Public relays may be offline or prune stored events; keep your own backup rather than relying on them for permanent archival.
