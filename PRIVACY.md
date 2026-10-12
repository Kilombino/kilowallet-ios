# Kilowallet privacy information

Draft for this iOS distribution, 2026-10-11. Original creator: **Kilombino**. Distribution maintained by romanmodin.

## Data on your iPhone

Recovery words and an optional BIP-39 passphrase are kept in a separate device-only Keychain item for each hot wallet. Watch-only wallets do not store recovery words. Real-device reads require Face ID, Touch ID or the device passcode. Wallet names, the selected wallet, transaction labels/notes, the hide-amounts preference, public account keys, shared server settings, accepted certificate fingerprints, receive/change-address progress and an unresolved signed transaction are stored locally in app preferences. A signed transaction is public transaction data, not a recovery secret. Local preferences may be included in operating-system backups; the recovery Keychain item is marked device-only.

Recovery words are brought into memory when you choose to reveal them or sign. The app obscures its interface while inactive and dismisses secret screens in the background. These controls do not guarantee that all screenshots, screen recording, memory copies or a compromised device can be prevented.

## Electrum servers and public transactions

The app connects to your selected Electrum server over TLS. The bundled default is `fulcrum.kilombino.com:17717`. That server receives your network address, queried address script hashes and wallet-history requests, and any transaction you submit. These queries can be linked to your wallet. The server is operated separately from this distribution; its logging and retention have not been verified here. You can choose your own compatible server in Settings.

Transactions broadcast to the network can become public blockchain records. Removing a wallet from the app does not erase blockchain records or data previously received by a server.

## Camera, clipboard and external links

Camera access is requested for QR scanning. The reviewed app decodes QR content locally; no image-upload path was identified. Paste/copy actions use the system clipboard. Avoid copying recovery secrets to the clipboard. Explorer and website links open only after you tap them; those sites apply their own privacy practices.

## Analytics and deletion

No first-party analytics, advertising or crash-reporting endpoint was identified in the reviewed app source. This statement does not cover operating-system diagnostics or independent server/website behavior. Removing a wallet deletes its Keychain secret, transaction labels/notes and active wallet identity; retained server pins and per-wallet public progress/pending-transaction records support safe recovery and are not a promise of full local-data erasure. Back up your recovery words before removal.

Source, issue-reporting guidance and updates are available at https://github.com/romanmodin/kilowallet-ios . Do not include recovery words, passphrases, private keys or other secrets in a public report.

## Optional history exports

Labels and notes stay in local preferences unless you explicitly export them. They may be
included in operating-system backups, but recovery words alone do not restore them. CSV export
includes the selected wallet name, loaded transaction history, amounts, labels/notes and the
snapshot time. Hidden amounts are included after an explicit export confirmation. A protected
temporary file is passed to the iOS share sheet; you choose the recipient or storage location.
The app deletes its temporary copy after sharing/cancellation, or on next launch if interrupted.
Copies saved or sent by you are outside the app's control. Exports do not contain recovery
words, passphrases or public account keys. The last successful sync time is session-only.
