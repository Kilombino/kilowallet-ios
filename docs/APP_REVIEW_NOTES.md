# App Review notes — Kilowallet for iOS 0.4.0 (build 4)

For the person who submits the app (App Store Connect → the version → **App Review Information →
Notes**). The first section is ready to paste; the rest explains what is behind it and how a
reviewer can see each part.

## Notes for the reviewer (paste into App Store Connect)

> Kilowallet is a self-custodial wallet for Bitcoin on the BLAKE2b proof-of-work fork of Bitcoin
> (ticker XBT). Keys are created on the device and kept in the iOS Keychain behind Face ID / Touch ID
> / passcode; they never leave the device. There is no account, no sign-up, no exchange, no buying or
> selling, no custody and no in-app purchase.
>
> New in 0.4: **Kilojoin**, shared transactions. Several users who each hold their own coins build
> ONE ordinary transaction together: each puts in coins and gets back outputs of the same amount to
> new addresses of their own (or pays one to five people through it). It is non-custodial: no server
> of ours ever holds, receives or can move anyone's funds — the coins go straight from each user's
> address to the outputs in a single transaction, and that transaction only exists once every user
> has signed it. Each user's app checks the whole transaction first (their own outputs, their change,
> their share of the network fee) and the user signs only their own inputs, only after that check
> passes, with Face ID. There is no fee for the feature: each user pays only their own share of the
> network (miner) fee, which goes to miners, not to us. The coordination messages are encrypted and
> carried over Nostr, an open protocol, through a public relay (relay.kilombino.com); the protocol is
> open and documented, and the same pools work with Kilowallet for Android and for desktop.
>
> Where to see it: the main wallet screen shows a "Kilojoin" panel → "open ›". The screen explains
> the feature and asks the user to turn it on; it then lists the pools open right now (it may be
> empty) and offers "Open a pool". From SEND, "or pay through a Kilojoin pool ›" opens the payment
> form. Taking part needs coins on the BLAKE2b chain; screenshots of every Kilojoin screen are
> attached.
>
> To look around without funds: on the first screen choose "Watch a zpub" and paste
> `zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs`
> (the public BIP-84 test vector wallet), or create a new wallet to see the backup flow and the
> Kilojoin screens (which then show that there are no coins to put in yet).

## What Kilojoin is, in plain terms

- **A collaborative transaction between users.** A "pool" is an announcement on a public relay: an
  amount, an address type, how many people, a fee rate, and how long it stays open. People who want
  to take part join it from their own wallets. When enough have joined, they agree to close it, and
  every wallet builds the very same transaction: all their coins as inputs, the pool's equal outputs
  to fresh addresses of their own, and each person's change. Each wallet signs its own inputs; once
  all signatures are in, any of them broadcasts the transaction to the network.
- **Non-custodial, from start to end.** Nobody — not us, not the relay, not the other members —
  holds, receives or can move anyone's coins at any point. There is no intermediate address and no
  deposit: the coins move only in that one transaction, which cannot exist until every member has
  signed it, and every signature covers the whole transaction (inputs and outputs), so it cannot be
  reused in any other one. If anyone leaves, does not sign, or the pool never fills, nothing happens
  and every coin stays where it was.
- **No fee.** The app charges nothing, and no part of the transaction goes to us. Each user pays
  their own share of the ordinary network fee (to miners), shown before they sign.
- **An open protocol over Nostr.** The messages that coordinate a pool travel through a Nostr relay
  (`relay.kilombino.com`), encrypted (NIP-44). Kilojoin is the same open protocol Kilowallet for
  Android and for desktop speak; its source is public (Apache-2.0) in
  `Kilombino/kilowallet` and in this repository (`core/src/commonMain/kotlin/.../kilojoin/`).
- **Each user signs only after verifying their own outputs.** Before the Sign button is enabled, the
  app checks the finished transaction against what the user agreed to: each of their outputs is
  there with its exact amount and address, their change is exactly right, their share of the fee is
  as announced, the other outputs are of the pool's equal amount (or the announced payments), and the
  total fee is in line with the rate. The screen shows that check ("Check before signing") and the
  user signs with Face ID. If anything is off, the app refuses to sign and the round is cancelled;
  no coin moves.
- **Payments through a pool.** From SEND, a payment to one to five recipients can go through a new
  pool: the recipients get their amounts in the same transaction as everyone else's outputs. The
  payment goes out only when the pool closes; if it never fills, nothing is paid.

## How a reviewer can see each screen

There is no special review mode in release builds (no hidden switch, no account). The feature is
always in sight:

1. Main screen (any wallet) → panel **KILOJOIN** → **open ›**.
2. First time: an explanation of what Kilojoin is, that nobody holds the coins, that there is no fee,
   and that iOS keeps no app running in the background (rounds run while the app is open). Buttons
   **NOT NOW** / **TURN ON**.
3. After TURN ON: the note about keeping the app open during a round, **＋ OPEN A POOL**, the list
   of **OPEN POOLS** (live from the relay; it may be empty), where the equal outputs go (this wallet,
   or a watch-only key the user adds), and "How a round goes" / "About Kilojoin".
4. **＋ OPEN A POOL**: the pool's amount (from whole coins with no change, or typed), equal outputs
   per person, fee rate, number of people, hours open, optional password; the coins to put in.
5. **JOIN** on an open pool: the pool's terms, the user's coins, the fee share, then
   **JOIN WITH FACE ID** (joining signs nothing that can move coins; it proves ownership).
6. A round in progress shows its status; when everyone is in, **CHECK BEFORE SIGNING** lists what
   was checked and **SIGN WITH FACE ID** signs.
7. SEND → **or pay through a Kilojoin pool ›** → recipients (one to five) → the same form as 4.

Taking part for real needs coins on the BLAKE2b chain and other people in the pool, which a
reviewer will not have. For that reason, attach screenshots (or a short screen recording) of the
screens above. They come from the project's own CI: every push builds the app for the simulator and
saves screenshots of each screen in the **screenshots** artifact of the GitHub Actions run
(`11-pool-intro`, `12-pools`, `13-pool-join`, `14-pool-create`, `15-pool-pay`, `16-pool-round`, plus
Spanish and Chinese ones). They are taken by a Debug-only launch argument (`-kwDemo <screen>`) that
fills the screens with made-up pools and coins held in memory; Release builds ignore it. To make
them yourself: run `scripts/ci-screenshots.sh` with a Debug simulator build, or launch the Debug app
from Xcode with the argument `-kwDemo pools` (or `pool-round`, `pool-create`, `pool-join`,
`pool-pay`, `pool-intro`).

## Guidelines this touches

- **2.3.1 (accurate metadata, no hidden features):** Kilojoin is visible on the main screen and in
  SEND, explained on first use, documented here and in the README, and should be named in the App
  Store description and in "What's New" (suggested text below).
- **3.1.5(b) (cryptocurrencies):** a wallet app; it must be submitted from an **organization**
  account. It stores and moves only the user's own coins, has no exchange, no ICO, no mining, and
  takes no fee. Kilojoin is the user's own wallet signing a transaction of the user's own coins
  together with other users; nothing is held by anyone else.
- **5.1 (privacy):** no data collected. The app connects to the Electrum server (default
  `fulcrum.kilombino.com`), to `mempool.kilombino.com` for prices (unless turned off), and — only
  after the user turns Kilojoin on — to the Nostr relay `relay.kilombino.com`. The privacy label stays
  "Data Not Collected".

## Suggested "What's New in This Version" (0.4.0)

> Kilojoin: shared transactions. Join a pool with other Kilowallet users, or open one, and each
> person gets back outputs of the same amount to fresh addresses of their own, in one transaction
> that everyone signs and nobody else holds. You can also pay up to five people through a pool from
> Send. Your wallet checks the whole transaction before you sign; there is no fee for it. Works with
> Kilowallet for Android and desktop. Also: the language tables of Kilowallet for Android 1.3.0.

## Suggested addition to the App Store description

> Kilojoin — shared transactions: take part in pools with other Kilowallet users (on iPhone, Android
> or desktop). Everyone puts in their own coins and gets back outputs of the same amount, in one
> transaction that each person checks and signs. No one holds your coins, and there is no fee: you
> only pay your share of the network fee. Rounds run while the app is open.

## One thing to check before submitting: export compliance

`Info.plist` still declares `ITSAppUsesNonExemptEncryption = NO`, as in 0.3. Since 0.4, Kilojoin
encrypts its coordination messages in the app itself (NIP-44: ChaCha20 and HMAC-SHA256, standard
algorithms), in addition to TLS from the OS and digital signatures. That is ordinary mass-market
encryption, but App Store Connect's export compliance questions ask specifically about encryption
implemented in the app with standard algorithms. Please answer those questions for 0.4.0 as they
apply (and set `ITSAppUsesNonExemptEncryption` to match what you answer), rather than reusing 0.3's
answer.

## Build

Unchanged from 0.3: see the README's "Hand-off: build, sign and upload". Version 0.4.0, build 4
(`iosApp/Configuration/Config.xcconfig`). No new permissions, entitlements, background modes or
third-party SDKs: rounds use the background time iOS gives any app after it leaves the screen
(`beginBackgroundTask`), nothing more.
