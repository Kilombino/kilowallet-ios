# Kilowallet for iOS

A self-custodial wallet for Bitcoin on the BLAKE2b proof-of-work chain (BTC), for iPhone.
The iOS sibling of [Kilowallet for Android](https://github.com/Kilombino/kilowallet).

Version 0.4 (BTC only):

- Create a wallet (12 or 24 words; the words are shown and then checked), restore from words
  (optional BIP-39 passphrase), or watch-only from a zpub (also ypub / xpub; a plain xpub asks
  which addresses it makes: bc1q, 3… or 1…, and warns when it is not an account key).
- Balance and movements with confirmations, with an SPV check of recent incoming payments against
  the block header's BLAKE2b proof of work (164-byte headers).
- Receive: next unused address with a QR code (CoreImage) and copy; ◀ ▶ move through every
  unused address (also the ones skipped below the last used one). Optionally an amount to ask
  for, in sats or in your currency: the QR code and COPY then carry a BIP-21 request.
- Send: address (paste, or scan a QR with the camera), amount or MAX, fee rate (server suggestion),
  review screen with a second confirmation for high fees, signing with the **unified sighash 0x21**
  (replay protection: the transaction is invalid on the SHA-256 chain), broadcast over Electrum TLS.
  Every coin is checked against the transaction that created it before anything is signed.
  The amount can be typed in sats or in your currency; either box fills the other.
- Speed up or cancel an unconfirmed send (replace-by-fee): ⚡ speed up keeps the payment and takes
  a higher fee from its change; ✕ cancel sends the same coins back to a fresh address of this
  wallet, so the recipients get nothing (unless the original confirms first). Either pays at least
  the old fee plus 1 sat/vB of its own size, as the network requires.
- Values in one of 36 currencies (USD, EUR, GBP, JPY, CHF, CNY, … ), or none. Prices come from
  `mempool.kilombino.com` (the ECB's daily reference for the exchange rates); the request is the
  same for everyone and carries nothing about the wallet. With the currency off, it is never made.
- Electrum server: `fulcrum.kilombino.com:17717` (TLS) by default, or your own. Certificates are
  self-signed, so they are pinned on first use; a changed certificate is refused until you accept it.
  The app refuses a server whose headers are not 164 bytes (i.e. not on the BLAKE2b chain).
  Electrum protocol 1.4 to 1.6 (the server picks; Shulcrum and Frigate speak 1.6), and address
  statuses and coins are asked in batches of 50, one round trip each.
- Every output held to its own type's dust limit (294 bc1q, 330 bc1p/P2WSH, 540 P2SH, 546 P2PKH),
  for the recipient and for the change.
- Languages: English, Español, Català, Deutsch, Français, Italiano, Português, 中文 — the Android
  app's own tables. The phone's language by default (English if the app doesn't have it), or one
  chosen in Settings. Texts only the iOS app has are translated in `scripts/i18n-ios/`, and the
  camera and Face ID permission texts in `iosApp/iosApp/<lang>.lproj/InfoPlist.strings`.
- Recovery words in the iOS Keychain, unlocked by Face ID / Touch ID / the device passcode.
- **Kilojoin** (shared transactions; the KILOJOIN screen, from the wallet's main screen): pools
  where several people make one transaction together. Each person puts in coins and gets back
  outputs of exactly the pool's amount, all equal, to fresh addresses of their own; or pays 1 to 5
  recipients through a pool from SEND. Join an open pool, or open one (from whole coins with no
  change, or with an amount typed in), with 1 to 5 equal outputs each. Each member's wallet checks
  the whole transaction (its own outputs, its change, its fee share, the other outputs) and the
  user signs only their own inputs, with Face ID, after seeing that check. Equal outputs go to this
  wallet's fresh addresses, or to a watch-only key's (a zpub) when one is set. No server holds or
  can move any coin; there is no fee for it; the messages go over nostr
  (`relay.kilombino.com`), encrypted. Kilojoin protocol v1 to v5, byte for byte as Kilowallet for
  Android 1.3.0 and for desktop: the same pools, with the same people. Native SegWit (bc1q) pools
  only. iOS keeps no app running in the background: rounds run while the app is open, and for the
  short time iOS allows after leaving it; the screen says so.

New in 0.4 (ported from Kilowallet for Android 1.3.0): Kilojoin (pools, payments through a pool,
Kilojoin v1 to v5), the language tables of Android 1.3.0, and receive and change addresses that a
round counts on are not handed out to anything else.

New in 0.3 (ported from Kilowallet for Android, up to 1.2.6): speed up and cancel an unconfirmed
send (RBF), 36 currencies with amounts in your currency in Send and Receive, a payment request with
an amount on the receive screen, the address type asked for a plain xpub, a refresh kept to the
wallet and server it started for, and the language tables of Android 1.2.6.

New in 0.2 (ported from Kilowallet for Android, up to 1.1.7): Electrum protocol negotiation up to 1.6,
batched requests, per-type dust limits, ◀ ▶ on the receive screen, languages.

Not in 0.4: other chains, Ark, Lightning, notifications (also for rounds: no lock-screen cards or
alerts), Taproot pools, rounds that sign on their own, the "perfect coin" made for a pool, speeding
up a payment received (CPFP), PSBT, Taproot accounts (also for watch-only), a full header chain
(SPV is per payment, as in 0.1).

## Official builds

The App Store build of Kilowallet for iOS, bundle ID `com.kilombino.kilowallet`, is published by
[romanmodin](https://github.com/romanmodin) on Kilombino's behalf and with his permission, from
[romanmodin/kilowallet-ios](https://github.com/romanmodin/kilowallet-ios). Any other build under
that name or bundle ID is not official.

## Hand-off: build, sign and upload (for the iOS developer)

Requirements: an Apple Developer account **enrolled as an organization** (App Store Review
Guideline 3.1.5(b): wallet apps for virtual currencies must come from an organization account, not
an individual one), a Mac with Xcode 16 or newer, a JDK 25 (e.g. `brew install --cask temurin@25`, or
any JDK 25 on `JAVA_HOME`). No CocoaPods, no SPM packages: the
Kotlin code is built by Gradle from Xcode's "Compile Kotlin Framework" build phase.

1. Clone the repo and, once, build the Kotlin side from a terminal so Gradle downloads what it
   needs (a few minutes the first time):

   ```sh
   git clone https://github.com/Kilombino/kilowallet-ios.git && cd kilowallet-ios
   ./gradlew :core:jvmTest :app:linkDebugFrameworkIosSimulatorArm64
   ```

   If Xcode later says it cannot find Java, make sure `JAVA_HOME` points at a JDK 25 for GUI apps
   too (or start Xcode from that terminal with `open iosApp/iosApp.xcodeproj`).

2. Open `iosApp/iosApp.xcodeproj` in Xcode (a plain project, not a workspace).

3. Select the **iosApp** target → **Signing & Capabilities**: choose your **Team**, and set the
   **Bundle Identifier** (suggested: `com.kilombino.kilowallet`). The same two values can instead
   be set once in `iosApp/Configuration/Config.xcconfig` (`TEAM_ID`, `BUNDLE_ID`). The version is
   `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in that file (0.4.0 / 4); raise the build
   number for every upload.

4. Try it: pick an iPhone simulator or a connected iPhone and press Run. In the Simulator, enable
   Face ID (Features → Face ID → Enrolled) to test unlocking the words.

5. **Product → Archive** with "Any iOS Device (arm64)" selected. When it finishes, the Organizer
   opens: **Distribute App → App Store Connect → Upload**. Then in App Store Connect add it to
   TestFlight, and later submit for review.

   The app is iPhone-only, portrait, dark; it declares no third-party SDKs, no tracking and only
   exempt encryption (`ITSAppUsesNonExemptEncryption = NO`: it uses the OS's TLS and digital
   signatures, which are exempt — confirm this in App Store Connect's export compliance questions).
   A privacy manifest (`PrivacyInfo.xcprivacy`) lists the "required reason" APIs it uses.

### App Store review notes (suggested text)

The full notes for the reviewer, including Kilojoin (0.4) and how to see it, are in
[`docs/APP_REVIEW_NOTES.md`](docs/APP_REVIEW_NOTES.md). In short:

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

- **Data not collected.** No analytics, no crash reporting service, no advertising, no tracking.
- Network: only the Electrum server the user chooses (default `fulcrum.kilombino.com:17717`). As
  with any light wallet, that server learns which addresses the wallet asks about; Settings explains
  this and lets the user use their own server. Tapping "open on mempool.kilombino.com" opens the
  browser, only on that tap.
- Prices: unless the currency is set to Off, one HTTPS GET of
  `https://mempool.kilombino.com/api/v1/blake2b/widget` at most every 5 minutes while the app is
  open. The request is the same for every user (no address, key or identifier in it).
- Kilojoin (only once the user turns it on in its screen): a WebSocket to the nostr relay
  `relay.kilombino.com` while that screen is open or a round is going, to list open pools and carry
  a round's messages (encrypted with NIP-44, signed by keys made for that pool). The relay sees the
  phone's IP address and when it is connected, never the wallet's keys; what the pool's other
  members learn is what the transaction shows on the chain anyway.
- Camera: only to scan a QR code, only when the user taps "Scan QR".
- On device: the recovery words (Keychain, this device only, user presence required), and in
  `UserDefaults` the wallet's public key, the server and its certificate fingerprint, the chosen
  currency and the last prices, and the state of the user's Kilojoin rounds (their terms, the
  one-off keys made for each pool, never the wallet's own keys; kept a week after a round ends).

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
(v0.26.0)**, brought up to
[`ed7177f`](https://github.com/Kilombino/kilowallet/commit/ed7177ff6f07f7019809905da5a0aadecd6eab6b)
(1.1.7) in 0.2 for the Electrum client, the dust limits, the receive screen and the languages,
and to
[`193f30b`](https://github.com/Kilombino/kilowallet/commit/193f30b3927aa75ce731a1666c88cdf7a53b857d) (1.2.6) in 0.3 for
replace-by-fee (speed up, cancel), the currencies and the watch-only import, file by file (each
file says which Android file it comes from):

| here (`core/src/commonMain/.../core/`) | Android (`app/src/main/java/com/kilombino/pyblockwatch/`) |
|---|---|
| `crypto/Bech32, Bip39Wordlist, Ripemd160, Blake2b, TxParse` | `crypto/…` — copied, only JVM calls replaced |
| `crypto/Bip39, Bip32, Bip32Priv, Address, Base58, TxBuilder, BlockHeader` | `crypto/…` — same code, `BigInteger`/`javax.crypto` replaced |
| `crypto/Ec` | `crypto/Secp256k1, Ecdsa, NativeSecp` — now libsecp256k1 via secp256k1-kmp |
| `chain/ElectrumClient, Chain` | `chain/ElectrumClient, Chain` — over a platform socket, kotlinx.serialization |
| `wallet/Scanner, Send` | `data/Scanner`, `ui/WalletViewModel` (prepareSend, verifyCoins, confirmSend, fees, dustFor) |
| `wallet/Replace` | `ui/WalletViewModel` (prepareBump, prepareCancel) |
| `market/Fiat` | `data/Fiat`, `data/MarketFeed` (the price and the exchange rates only) |
| `kilojoin/Protocol, PoolTx, PoolSession, BlindRsa, NostrEvent, Nip44, Relay, PoolPayment` | `coinjoin/Protocol, CoinjoinTx, PoolSession, BlindRsa, NostrEvent, Nip44, RelayClient, CoinjoinPayment, MixedCoins, Destination` (1.3.0, [`6bb1bb0`](https://github.com/Kilombino/kilowallet/commit/6bb1bb0681a28a1e81be766e4be86e4d20f654ab)) — coroutines instead of threads, `BigNat` instead of `BigInteger` |
| `app/…/Pools.kt`, `app/…/ui/Pools.kt` | `coinjoin/CoinjoinHub`, `ui/CoinjoinScreen` — without the background service, the alerts and the lock-screen cards |
| `app/…/i18n/I18n, Text` + `composeResources/files/i18n/*.json` | `ui/i18n/I18n, Text` + `assets/i18n/*.json` (copied by `scripts/sync-i18n.py`) |

Why a copy and not a Gradle composite build / submodule of Kilombino/kilowallet: the Android
sources are JVM-only (`java.math.BigInteger`, `javax.crypto`, `java.net` sockets, `org.json`), so
they cannot be compiled for iOS as they are; sharing them for real would mean restructuring the
Android app into a multiplatform module first, which is a change to the production Android app.
Instead the port is pinned to the Android code by tests: `tools/android-vectors` compiles the
Android app's crypto files **unchanged** on the JVM and records what they produce (mnemonics,
seeds, zpubs, addresses, private keys, ECDSA signatures and 24 complete signed transactions, half
of them with the unified sighash, plus 9 unconfirmed sends each replaced by a speed-up and by a
cancellation at three fee rates); `AndroidVectorsTest` and `ReplaceTest` require byte-identical results here, on the
JVM and in the iOS simulator. The same tool runs the Android app's Kilojoin code unchanged
(`KilojoinVectors`: blind signatures with a fixed RSA key, nostr events and NIP-44 payloads, which
pool announcements are listed and as what, the v5 split, the per-person minimum, fee shares, the
messages' MACs and proofs, and whole v3, v4 and v5 rounds whose signatures, ground to a low R as
Bitcoin Core does, and raw transactions must come out identical), and `./gradlew -p
tools/android-vectors interop` plays real rounds between this port and the Android app's own
session code over relay.kilombino.com and a BLAKE2b regtest node of your own (`KW_RPC`). To re-check against a newer Android version:

```sh
./gradlew -p tools/android-vectors run -PandroidRepo=/path/to/kilowallet
./gradlew :core:jvmTest
```

The language tables are copied the same way, from a given Android commit; entries that call the
SHA-256 chain or the pools by the Android app's own words are left out (on iOS they are Corecoin
and Kilojoin, and the pool screens have texts of their own). Strings only iOS has can be translated in
`scripts/i18n-ios/<lang>.json`, merged on top:

```sh
scripts/sync-i18n.py /path/to/kilowallet <commit>
./gradlew :app:desktopTest -i     # fails if an iOS string has no Spanish; lists the rest per language
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
- Kilojoin: BIP-340 Schnorr (nostr events) and ECDSA ground to a low R (pool inputs) through
  libsecp256k1; NIP-44 v2 (ChaCha20 + HMAC-SHA256) and RSA blind signatures (RFC 9474,
  RSABSSA-SHA384-PSS-Randomized) in this repository, the RSA arithmetic in `kilojoin/BigNat`
  (tested against `java.math.BigInteger` and the RFC's vector).

### Tests and CI

`.github/workflows/ios.yml` (GitHub's macOS runners):

- `:core:jvmTest` and `:core:iosSimulatorArm64Test` — the Android vectors, Knots' unified sighash
  vectors, real BLAKE2b mainnet headers, BIP-39/BIP-84 official vectors, coin selection, dust
  limits, the Electrum client against a scripted server (1.4/1.6, batches);
- `:app:desktopTest` — the language tables and the translator, and every iOS screen string in
  every language; it fails if "coinjoin", "mix"/"mixing"/"mixer", "spamchain", "spamcoin" or
  "Poolcoin" (or their translations) appear in any iOS string table, `InfoPlist.strings` or
  `Info.plist`;
- a live smoke test against the default Electrum server (allowed to fail if it is down);
- builds the Xcode app for the simulator (Debug, unsigned) and, in a second job, for devices
  (Release, unsigned);
- boots a simulator, installs and launches the app and uploads screenshots of the main screens as
  the `screenshots` artifact. For that, Debug builds accept a `-kwDemo <screen>` launch argument
  that opens a screen with the public "abandon … about" test wallet held only in memory (the
  Keychain is not touched; the review screen uses a made-up coin). `-kwDemo receive@es` shows a
  screen in another language. Release builds ignore it.

Local development without a Mac: `./gradlew :core:jvmTest` runs all tests on the JVM,
`./gradlew :app:compileKotlinIosSimulatorArm64` compiles the iOS Kotlin code (not the Swift), and
`./gradlew :app:run -PkwDemo=home` opens the shared UI in a desktop window (preview only, never
shipped).

## License

Apache-2.0, like the Android app. See `LICENSE` and `NOTICE`.
