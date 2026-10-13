# Kilowallet for iOS

Created by **Kilombino**. This distribution is maintained by [romanmodin](https://github.com/romanmodin/kilowallet-ios); upstream authorship, Apache-2.0 license and NOTICE are preserved.

**Release candidate under validation — not yet approved for real funds.** See [the source review](audit/SECURITY_REVIEW_2026-10-11.md) and [release checklist](audit/RELEASE_READINESS.md).

A self-custodial wallet for Bitcoin on the BLAKE2b proof-of-work chain (BTC / XBT), for iPhone.
The iOS sibling of [Kilowallet for Android](https://github.com/Kilombino/kilowallet).

Version 0.1 (BTC only):

- Create a wallet (12 or 24 words; the words are shown and then checked), restore from words
  (12, 15, 18, 21 or 24 English BIP-39 words; first BIP-84 account only, optional passphrase), or watch-only from a zpub (also ypub / xpub).
- Balance and movements with confirmations, with an SPV check of recent incoming payments against
  the block header's BLAKE2b proof of work (164-byte headers).
- Receive: next unused address with a QR code (CoreImage) and copy.
- Send: address (paste, or scan a QR with the camera), amount or MAX, fee rate (server suggestion),
  review screen with a second confirmation for high fees, signing with the **unified sighash 0x21**
  (replay protection: the transaction is invalid on the SHA-256 chain), broadcast over Electrum TLS.
  Every coin is checked against the transaction that created it before anything is signed.
- Electrum server: `fulcrum.kilombino.com:17717` (TLS) by default, or your own. Certificates are
  pinned only after explicit fingerprint approval; verify the fingerprint independently before accepting it.
  A changed certificate is refused until you explicitly accept the replacement.
  The app refuses a server whose headers are not 164 bytes (i.e. not on the BLAKE2b chain).
- Recovery words in the iOS Keychain, unlocked by Face ID / Touch ID / the device passcode.

Not in 0.1: other chains, coinjoin, Ark, Lightning, notifications, fiat prices, PSBT.

## Hand-off: build, sign and upload (for the iOS developer)

See [the current signing runbook](distribution/SIGNING.md) for the prepared archive/export command and remaining Apple prerequisites.

Requirements: an Apple Developer account **enrolled as an organization** (App Store Review
Guideline 3.1.5(b): wallet apps for virtual currencies must come from an organization account, not
an individual one), a Mac with Xcode 16 or newer, a JDK 25 (e.g. `brew install --cask temurin@25`, or
any JDK 25 on `JAVA_HOME`). No CocoaPods, no SPM packages: the
Kotlin code is built by Gradle from Xcode's "Compile Kotlin Framework" build phase.

1. Clone the repo and, once, build the Kotlin side from a terminal so Gradle downloads what it
   needs (a few minutes the first time):

   ```sh
   git clone https://github.com/romanmodin/kilowallet-ios.git && cd kilowallet-ios
   ./gradlew :core:jvmTest :app:linkDebugFrameworkIosSimulatorArm64
   ```

   If Xcode later says it cannot find Java, make sure `JAVA_HOME` points at a JDK 25 for GUI apps
   too (or start Xcode from that terminal with `open iosApp/iosApp.xcodeproj`).

2. Open `iosApp/iosApp.xcodeproj` in Xcode (a plain project, not a workspace).

3. Select the **iosApp** target → **Signing & Capabilities**: choose your **Team**, and set the
   **Bundle Identifier** (suggested: `com.kilombino.kilowallet`). The same two values can instead
   be set once in `iosApp/Configuration/Config.xcconfig` (`TEAM_ID`, `BUNDLE_ID`). The version is
   `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in that file (0.1.0 / 1); raise the build
   number for every upload.

4. Try it: pick an iPhone simulator or a connected iPhone and press Run. In the Simulator, enable
   Face ID (Features → Face ID → Enrolled) to test unlocking the words.

5. **Product → Archive** with "Any iOS Device (arm64)" selected. When it finishes, the Organizer
   opens: **Distribute App → App Store Connect → Upload**. Then in App Store Connect add it to
   TestFlight, and later submit for review.

   The app is iPhone-only, portrait and dark. Review the actual crypto implementation and dependencies
   when answering Apple's export-compliance questions. The inherited
   `ITSAppUsesNonExemptEncryption = NO` value still needs confirmation before upload;
   it is not evidence that the declaration has been reviewed.
   A privacy manifest (`PrivacyInfo.xcprivacy`) lists the "required reason" APIs it uses.

### App Store review notes (suggested text)

> Kilowallet is a self-custodial wallet for Bitcoin on the BLAKE2b proof-of-work fork of Bitcoin
> (ticker XBT): same addresses and transaction history as Bitcoin up to block 961 640, a different
> proof of work after it. The user's keys are generated on the device and stored in the iOS
> Keychain behind Face ID / Touch ID / passcode; they never leave the device. There is no account,
> no sign-up, no exchange, no buying or selling, no custody and no in-app purchase: the app only
> shows the user's balance and builds and signs transactions that the user sends to an address
> they choose. It connects only to an Electrum server (by default fulcrum.kilombino.com, or one the
> user enters) to read the chain and broadcast transactions.
>
> To review without funds: on the first screen choose "Watch a zpub" and paste
> `zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs`
> (the public BIP-84 test vector wallet), or create a new wallet to see the backup flow.

### Privacy (for the App Store privacy label)

- No first-party analytics, crash reporting service, advertising or tracking was identified in the reviewed source. Review the selected server's data practices before completing Apple's privacy label; see [PRIVACY.md](PRIVACY.md).
- Network: only the Electrum server the user chooses (default `fulcrum.kilombino.com:17717`). As
  with any light wallet, that server learns which addresses the wallet asks about; Settings explains
  this and lets the user use their own server. Tapping "open on mempool.kilombino.com" opens the
  browser, only on that tap.
- Camera: only to scan a QR code, only when the user taps "Scan QR".
- On device: the recovery words (Keychain, this device only, user presence required), and in
  `UserDefaults` the wallet's public key, server/pins, receive/change progress and unresolved signed transaction. Preferences may be included in operating-system backups.

## How it is built

```
core/     Kotlin Multiplatform (JVM + iOS): crypto, Electrum client, scanner, coin selection, signing
app/      Compose Multiplatform UI (shared), iOS entry point (iosMain), desktop preview (desktopMain)
iosApp/   Xcode project: SwiftUI shell + the platform services the Kotlin code asks for
          (Keychain + LocalAuthentication, Network.framework TLS, CoreImage QR, AVFoundation scanner)
tools/android-vectors/  generates the test vectors from the Android app's own code
```

### Where the shared code comes from

The wallet logic is a **port of the Android app's code at commit
[`5dfd590`](https://github.com/Kilombino/kilowallet/commit/5dfd59054446d32229575b5839a3dbd7ab6b46b5)
(v0.26.0)**, file by file (each file says which Android file it comes from):

| here (`core/src/commonMain/.../core/`) | Android (`app/src/main/java/com/kilombino/pyblockwatch/`) |
|---|---|
| `crypto/Bech32, Bip39Wordlist, Ripemd160, Blake2b, TxParse` | `crypto/…` — copied, only JVM calls replaced |
| `crypto/Bip39, Bip32, Bip32Priv, Address, Base58, TxBuilder, BlockHeader` | `crypto/…` — same code, `BigInteger`/`javax.crypto` replaced |
| `crypto/Ec` | `crypto/Secp256k1, Ecdsa, NativeSecp` — now libsecp256k1 via secp256k1-kmp |
| `chain/ElectrumClient, Chain` | `chain/ElectrumClient, Chain` — over a platform socket, kotlinx.serialization |
| `wallet/Scanner, Send` | `data/Scanner`, `ui/WalletViewModel` (prepareSend, verifyCoins, confirmSend, fees) |

Why a copy and not a Gradle composite build / submodule of Kilombino/kilowallet: the Android
sources are JVM-only (`java.math.BigInteger`, `javax.crypto`, `java.net` sockets, `org.json`), so
they cannot be compiled for iOS as they are; sharing them for real would mean restructuring the
Android app into a multiplatform module first, which is a change to the production Android app.
Instead the port is pinned to the Android code by tests: `tools/android-vectors` compiles the
Android app's crypto files **unchanged** on the JVM and records what they produce (mnemonics,
seeds, zpubs, addresses, private keys, ECDSA signatures and 24 complete signed transactions, half
of them with the unified sighash); `AndroidVectorsTest` requires byte-identical results here, on the
JVM and in the iOS simulator. To re-check against a newer Android version:

```sh
./gradlew -p tools/android-vectors run -PandroidRepo=/path/to/kilowallet
./gradlew :core:jvmTest
```

### Cryptography

- secp256k1: [secp256k1-kmp](https://github.com/ACINQ/secp256k1-kmp) (ACINQ), i.e. Bitcoin Core's
  libsecp256k1, constant time, on iOS via cinterop and on the JVM via JNI. ECDSA nonces are
  RFC 6979 (deterministic), signatures low-S — byte-identical to the Android app.
- SHA-256 / HMAC: [KotlinCrypto](https://github.com/KotlinCrypto) (pure Kotlin). PBKDF2, RIPEMD-160,
  BLAKE2b, Base58, Bech32 are in this repository.
- Every BLAKE2b-chain spend is signed with the unified sighash (`SIGHASH_UNIFIED | SIGHASH_ALL` =
  0x21, Knots v29.4.1 `doc/unified-sighash.md`), tested against Knots' 142 reference vectors; the
  code has no path that signs a spend with plain SIGHASH_ALL (`TxBuilder.build` defaults to
  unified, and `Signer` always asks for it).
- Entropy for new words: `SecRandomCopyBytes`.

### Tests and CI

`.github/workflows/ios.yml` (GitHub's macOS runners):

- `:core:jvmTest` and `:core:iosSimulatorArm64Test` — the Android vectors, Knots' unified sighash
  vectors, real BLAKE2b mainnet headers, BIP-39/BIP-84 official vectors, coin selection;
- an optional live smoke test only when an independently approved certificate pin is configured; failures are not ignored;
- builds the Xcode app for the simulator (Debug, unsigned) and, in a second job, for devices
  (Release, unsigned);
- boots a simulator, installs and launches the app and uploads screenshots of the main screens as
  the `screenshots` artifact. For that, Debug builds accept a `-kwDemo <screen>` launch argument
  that opens a screen with the public "abandon … about" test wallet held only in memory (the
  Keychain is not touched; the review screen uses a made-up coin). Release builds ignore it.

Local development without a Mac: `./gradlew :core:jvmTest` runs all tests on the JVM,
`./gradlew :app:compileKotlinIosSimulatorArm64` compiles the iOS Kotlin code (not the Swift), and
`./gradlew :app:run -PkwDemo=home` opens the shared UI in a desktop window (preview only, never
shipped).

## License

Apache-2.0, like the Android app. See `LICENSE` and `NOTICE`.


## Multiple wallets

Open **Wallets** from the home-screen wallet name or Settings. Add a new wallet,
restore a separate backup, or import a watch-only public key; name and switch
between up to 20 wallets. Each hot wallet has its own device-only,
authentication-protected Keychain item. Back up each wallet separately.

The existing single wallet migrates automatically without moving or reading its
recovery words. Public receive/change progress and pending transactions remain
associated with their account key. Switching clears displayed balances, send
drafts and unlock callbacks, then refreshes only the selected wallet. Sending
cannot switch wallets while signing/broadcasting. Server selection and
certificate approvals are shared across wallets and are labelled accordingly.

Remove the selected wallet in Settings after backing it up. Removing watch-only
wallets never deletes recovery words; removing one hot wallet does not affect
another. A pending payment must be resolved before its wallet can be removed.
Public address high-water marks remain available for safe re-import, preventing
accidental address reuse. A failed setup commit can be completed on restart from
a journal containing only the new public wallet identity; recovery words never
enter preferences.

## Everyday tools

- **Labels and notes:** tap a transaction to name it (80 characters) and add a note (500).
  Notes are local to the account, survive renaming/restarting, and are removed when that
  wallet is removed. They are not recovered from seed words; export CSV to keep a separate copy.
- **Hide amounts:** the home-screen toggle persists across wallets and launches. It hides
  balances, history amounts/notes, and spendable/send-all totals. An explicit payment review
  still shows the amount, fee and change so a user can verify what they sign.
- **Receive requests:** optionally enter a positive whole-satoshi amount. The QR and
  Copy payment request encode an exact BIP21 amount plus `chain=xbt`; Copy address remains
  available separately. Invalid or overflowing amounts never produce a request QR.
  Amount edits keep the same reserved address. The chain label is advisory—use an
  XBT-compatible sender; other Bitcoin wallets may ignore it.
- **History CSV:** after a successful sync, export all loaded transactions for the selected
  wallet via the iOS share/save sheet. The confirmation explains that amounts and notes are
  included even when hidden. Columns include network, wallet name, txid, signed net sats,
  confirmations/status, block height, label/note, and snapshot UTC time. Unknown amounts stay
  blank. Net outgoing amounts include fees; there are no invented transaction dates or fiat
  valuations. This is a loaded-history snapshot, not a tax report. Free text is escaped and
  spreadsheet-formula prefixes are neutralized.
- **Last successful sync:** shown in UTC for the currently loaded snapshot. Failed refreshes
  do not advance it. Switching wallet/server or restarting clears it until a successful scan;
  the app does not pretend an old timestamp proves a newly displayed balance is current.

CSV preparation never reads recovery words. Files are created only after confirmation in a
protected temporary directory; the share sheet controls the destination. Temporary files are
removed after the sheet closes or on the next app launch after interruption. Physical-device
share/save and authentication checks remain part of release validation.

## Coin control and daily tools

In Send, expand **Coin control** to freeze/unfreeze individual outputs or choose exact coins.
Automatic selection and MAX exclude frozen outputs. Manual mode uses every selected output
and never adds another coin to cover a shortfall. An empty, missing or frozen selection blocks
the payment. The full input list is available in Review → Technical details. Frozen policy is
checked before authentication and again before signing; corrupt policy blocks new payments.
Freezing cannot cancel an already signed pending payment. Combining outputs can link them
publicly. Frozen records persist per account across restarts but are local settings, not a
network lock, and recovery words alone do not restore them. Removing a wallet clears its policy.

Other additions:
- Saved XBT recipients per wallet, editable in Settings or selectable from Send. Always verify
  the complete address and chain with the recipient; identical address formats exist on BTC.
- Search the full loaded history by transaction ID, label or note; filter incoming, outgoing
  or pending, and load additional matching rows. CSV remains a full loaded-history export.
- Persistent XBT/sats units in Settings for balances, movements, send/receive amounts and
  payment review. XBT uses exact integer-satoshi conversion with at most eight decimals.
  Fee rates and CSV numeric fields retain their explicit satoshi units.
- Receive → Share QR and request opens the native iOS share sheet with the request and QR.
  This is user-initiated and shares only the public payment request, with no recovery secrets.
- Settings → Check written backup authenticates, then locally derives the account from the
  words and exact optional passphrase you type. A match records only a per-wallet check time;
  entries are not persisted. Leaving/backgrounding cancels the check. This verifies the account
  backup, not local notes, recipients, settings, every address type, or other accounts.

Before release, exercise coin selection/freezing and backup entry with disposable wallets on
a physical iPhone, and check QR/request sharing to actual destinations. Automated test success
and unsigned builds alone do not establish signing or TestFlight readiness.
