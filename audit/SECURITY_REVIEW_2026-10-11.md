# Kilowallet iOS intake and security review

Reviewed for romanmodin on 2026-10-11. This is an engineering source review, not a security certification.

This document describes the **upstream baseline**. Subsequent fixes and current release gates are tracked in [RELEASE_READINESS.md](RELEASE_READINESS.md); the findings below remain as the original evidence.

## Disposition

**Keep for development; address the release blockers below before a real-funds release.** No deliberate backdoor, seed-exfiltration route, hidden executable payload, or unexplained network destination was identified in the reviewed first-party source. That is a bounded finding, not proof that the app and every dependency are free of malicious code or defects.

- Upstream: https://github.com/Kilombino/kilowallet-ios
- Reviewed source: `e20e31edf63298d38f1b1fcc0a09a0922fa4abef` (2026-10-10 16:16:51 UTC).
- Its only parent: `6592d65d7fd529e2b453968c9f33b6d77246c0c3` (initial import).
- Fork: https://github.com/romanmodin/kilowallet-ios
- Apache-2.0 `LICENSE`, `NOTICE`, author attribution and original Git history retained.
- The supplied X post could not be fetched. The public repository above matches the developer, app name, iOS handoff and Xcode project described in the supplied screenshot.
- This intake adds review documentation and isolated characterization tests. It does not change production wallet behavior, add signing credentials, submit to Apple, or connect real wallets.

## What was checked

Inventory of all **82 upstream tracked files**, both commits, file modes, build inputs, Gradle/Xcode/CI scripts, runtime network entry points, seed generation/storage/reveal/signing paths, address parsing and derivation, transaction construction, server response handling, UI lifecycle and recovery behavior. The compact source is Kotlin Multiplatform plus a Swift platform bridge.

`evidence/source-inventory.json` records per-file SHA-256 hashes and the scan results. There are no tracked submodules or symlinks. The only non-text files are two PNG images and the Gradle wrapper JAR:

- Wrapper JAR SHA-256 `76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3` matches Gradle's official 9.1.0 wrapper checksum. The shell/batch launchers differ from Gradle's tagged scripts only by omission of the default UTF-8 JVM option (batch line endings normalized for comparison).
- Both PNG files have valid chunk CRCs, ordinary image chunks, and zero bytes after `IEND`. This is a structural image check, not a proof against steganography. No code path was found that treats image data as executable instructions.
- BIP-39's 2,048-word list exactly matches the canonical Bitcoin BIPs English list, SHA-256 `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`.
- No Unicode format-control characters were found in tracked text. Pattern scans of both commits found no private-key PEM headers or the checked GitHub/AWS/Slack credential formats. These patterns are not exhaustive secret detection. Test vectors intentionally contain public test private keys and seed phrases; never fund them.
- OSV returned no advisory matches for the **11 direct declared Maven coordinates** queried. This is not a resolved/transitive dependency audit; platform-specific artifacts, build plugins and their dependencies still require examination. See `evidence/direct-dependency-advisories.json`.

### Runtime data paths

| Path | Observed behavior |
| --- | --- |
| Recovery words and optional passphrase | Stored together in one device-only Keychain item; real-device reads require user presence. Swift returns the secret to Kotlin for reveal/signing. No network transmission of the secret was found. |
| New wallet entropy | iOS `SecRandomCopyBytes`, checked for failure. |
| Automatic network traffic | Electrum server chosen by the user; default `fulcrum.kilombino.com:17717`. The server sees address script hashes, queries, IP and broadcasts. |
| Explorer / website links | Browser navigation after a user tap to the documented explorer, GitHub, or author website. |
| Preferences | Public extended key, wallet kind, server and certificate fingerprint in UserDefaults. |
| Demo wallet | Swift launch-argument entry is `#if DEBUG`; uses in-memory preferences and seed store. Release entry returns no demo argument. |
| Dependencies | Declared Maven/Gradle repositories and standard tooling; no first-party runtime downloader or evaluator was found. Dependency binaries are outside the completed manual source review. |

Positive checks include raw transaction ID verification, checking each spent output's value and ownership script, matching derived signing keys to the wallet public key, and explicitly requesting unified sighash `0x21` in the production signer. These reduce several common wallet risks but do not resolve the findings below.

## Findings and required improvements

Severity is an engineering release priority, not a CVSS score. **Source-confirmed** means the code path was traced; **characterization** refers to the isolated tests below. Physical-device observations are explicitly distinguished.

### H1 — Public hostnames can trigger plaintext fallback

**Source-confirmed; characterization test included.** `core/.../chain/ElectrumClient.kt:57–88` classifies hosts by string prefixes rather than parsed addresses. `fd-wallet.example`, `10.wallet.example`, `192.168.wallet.example`, and `172.16.wallet.example` are treated as local. On a TLS error, an unpinned connection to one of these names retries using plaintext. This concerns custom unpinned servers; the bundled default does not match those strings, and a stored pin prevents fallback.

**Impact:** silent loss of transport confidentiality/authentication, allowing observation or manipulation of wallet queries and server replies. This is not evidence of seed transmission.

**Fix:** TLS by default with no automatic downgrade. Make local plaintext a separate explicit setting; validate literal private/loopback addresses, and handle resolved addresses and DNS changes deliberately. Add downgrade and pin-change tests.

### H2 — Secret screens have no app-background protection

**Source-confirmed missing control; physical snapshot behavior not tested.** Recovery words live in `Screen.CreateShow`, `Screen.CreateConfirm` and `Screen.ShowWords`; restore words/passphrase use ordinary text fields. `iosApp/iosApp/ContentView.swift` and `iOSApp.swift` have no scene/background obscuring or relock behavior. The fields disable autocorrect but are not secure-entry fields.

**Impact:** sensitive content can remain visible after background/resume and may appear in the app-switcher snapshot; passphrases are plainly displayed. Keychain protection does not protect a secret already on screen.

**Fix:** cover the app before background snapshots, clear/dismiss revealed secrets, require fresh authentication on return, use suitable secure input for the passphrase, and test device locking, interruption, app switching and screen capture behavior on an iPhone. Do not claim screenshots can be universally prevented.

### H3 — Pending operations are not bound to the current wallet/review

**Source-confirmed design gap; race behavior needs model/device tests.** `WalletModel.kt:181–193, 301–355` cancels only `scanJob` when deleting a wallet. Setup, fee/UTXO fetch, prepare-send, reveal and Keychain callbacks have no wallet-generation/request token. `confirmSend()` sets Signing only after authentication returns, and its closure captures a prior draft/server. `back()` and `editSend()` do not invalidate it; repeated taps can queue work. The signer checks key/public-key agreement, which prevents simply signing a different wallet's coins, but does not enforce current UI intent.

**Fix:** one operation owner per wallet, cancel/invalidate outstanding work on back/edit/delete/server change, transition to authentication state before prompting, enforce single-flight signing, and recheck exact reviewed transaction identity immediately before signing/broadcast. Add delayed-callback tests with no real funds.

### H4 — Chain and confirmation trust is weaker than the UI suggests

**Source-confirmed; header-gate characterization included.** `ElectrumClient.kt:72–78` checks only a 328-character header length; non-hex content of that length is accepted during connect. `Scanner.kt:265–281` verifies a recent incoming transaction against one supplied header and a difficulty floor, without anchoring it to a validated header chain or accumulated work. Its SPV cache is keyed only by transaction ID, not block hash/height/server, and is not invalidated for reorgs. `Home.kt:135–145` displays a confirmation checkmark without consulting `spv`.

**Impact:** a dishonest/stale server can misrepresent chain identity or confirmations; a previously checked proof can remain stale. Real PoW and valid merkle checks still have value, but they do not establish that a header is on the accepted best chain.

**Fix:** validate header encoding/version/known chain checkpoints and header linkage/work, tie proof caches to block identity and invalidate on reorg/server changes, and visibly distinguish server-reported, verified, unknown and failed proofs.

### M1 — Broadcast receipts are not matched to the signed transaction

**Source-confirmed; characterization included.** `ElectrumClient.kt:205–208` accepts any returned string of length 64, including non-hex text. `WalletModel.kt:346–350` discards the locally computed signed transaction ID and displays the returned string. On error, `Send.kt:44` says “Not sent,” even though a broadcast timeout can occur after server acceptance.

**Fix:** persist the local transaction ID and bytes before broadcast, require the returned ID to match, reconcile uncertain outcomes, and say “broadcast status unknown” when appropriate. A matching reply alone still does not prove network propagation or confirmation.

### M2 — Untrusted data and monetary arithmetic lack bounds

**Source-confirmed; duplicate-input and overflow characterization included.** `ElectrumConnection.swift:108–127` can accumulate an unlimited line. JSON-RPC waits can consume endless malformed/unrelated messages. `TxParse.kt:11–29` converts untrusted counts to Int without size/canonicality budgets. `SendPlanner` accepts duplicate outpoints and unchecked Long arithmetic; `Long.MAX_VALUE` as an amount can yield a draft with a negative fee. Non-finite rates are not explicitly rejected.

**Fix:** frame/JSON/transaction/history/count limits and absolute request deadlines; strict integer/money range checks and checked sums; duplicate outpoint rejection; finite bounded fees; fuzz truncated/oversized/malformed inputs. Do not run an unbounded memory exhaustion demonstration on a user's device.

### M3 — Keychain replacement deletes the old secret before success

**Source-confirmed.** `Keychain.swift:22–32` calls `delete()` before creating the access control and adding the replacement. `delete()` ignores status. A failed save can lose the previous item; concurrent setup operations are not guarded. Several derivation intermediates and immutable secret strings also outlive the intended wipe: for example the unnamed purpose node in `Bip32Priv.accountXpub`, HMAC intermediate buffers and exception paths.

**Fix:** transactional/staged replacement with success verification and rollback, propagate Keychain failures, protect wallet removal, and shorten secret lifetimes with cleanup in `finally`. Complete guaranteed wiping of immutable managed strings cannot be promised.

### M4 — Certificate pinning begins too late

**Source-confirmed.** `WalletModel.kt:205–219, 258–261` persists the first fingerprint only after the complete scan succeeds, although the Connected event already supplies it. A server that completes TLS and receives address queries but interrupts scanning can leave the app unpinned for later connections. Initial trust is silent TOFU, not CA/hostname verification.

**Fix:** make the first trust decision explicit and persist the accepted server identity before sending wallet queries; bind every subsequent connection to it. Provide a carefully designed renewal/change flow.

### M5 — Receive/change address progress is not persisted

**Source-confirmed.** `WalletModel.kt:288–296, 324` computes indices solely from current scan results. Before a completed scan, after a server change, or after incomplete history, it can return index zero/an older address. `Home.kt:178` remembers the receive address without observing scan updates. The app does not reserve an issued address, so issuing several unpaid invoices reuses it.

**Fix:** persist per-wallet highest-used and highest-issued receive/change indices, reserve addresses, display scan state, and ensure restore discovery covers the chosen issuance/gap policy.

### M6 — Supply-chain and CI integrity need strengthening

**Source-confirmed.** The upstream wrapper lacks `distributionSha256Sum`; dependencies lack lockfiles and verification metadata; upstream Actions use movable version tags and do not set explicit minimum permissions. The two Xcode build steps use `... || true` followed by a directory-existence check, which is weaker than checking the build exit status and actual executable. The live-server smoke test is allowed to fail.

**Fix:** commit independently checked Gradle distribution and dependency hashes, lock resolved dependencies, inventory transitive/platform-native artifacts, pin CI actions, minimize token scope and credential persistence, and fail builds on the real exit status. The added audit workflow uses pinned Actions/read-only permissions, verifies the wrapper, applies an official distribution checksum for that run, and uses strict Xcode exit checking. It has no signing or publication steps.

## Product improvements after hardening

1. Use **XBT** consistently in amount/send/receive labels, with a clear BLAKE2b network badge. Current `Chain.COIN = "BTC"` is unnecessarily confusing.
2. Strict payment-URI handling: check network markers, reject unsupported required parameters, bound input length, preserve exact satoshi amounts and confirm the destination/network. `parsePaymentText` currently ignores everything except amount/address.
3. Restore discovery across supported account indices and script types, with honest support limits. Current hot recovery is account 0, BIP-84, 12/24 English words; it will not discover all wallets implied by “any standard wallet.” Offer advanced recovery and progress/cancel/retry.
4. Better send review, fee estimates, coin control, fee bumping and persistent pending-transaction history; validate the planned outputs again at the signing boundary.
5. Multiple independent wallets/accounts, labels and backup-health checks. Implement isolation and deletion tests before expanding account support.
6. User-controlled server profiles and recovery from outages; explain address-query privacy before switching or adding servers. Avoid silently disclosing the same wallet to many servers.
7. Hardware-wallet/PSBT QR support with explicit XBT chain/replay rules, followed by optional price display using XBT data. These are later features, not substitutes for the security work above.

## Validation and limitations

The original upstream run `38067020512` reports success for the reviewed commit; that is upstream evidence, not an independent clean bill of health.

Independent fork workflow: https://github.com/romanmodin/kilowallet-ios/actions/runs/38139630578 . Final results are recorded separately in `evidence/validation.json`.

The audit-only `repro/AuditCharacterizationTest.kt` contains five defect characterizations plus one pin-preservation control (six test methods). They use mocked transports and artificial inputs. **A passing characterization means the unsafe baseline behavior was reproduced.** They are not security regression tests and should be replaced by tests asserting rejection when fixes are implemented. The baseline workflow at commit `53128fb` copied them temporarily into `commonTest`; they are not included in production source sets.

Not completed: full third-party/transitive source review, binary-to-source reproducibility for dependencies, a signed IPA assessment, real-iPhone lifecycle/Keychain/QR testing, runtime network capture, an independent cryptographic audit, or App Store publication. No real wallet secrets or funded transactions were used. These limitations prevent any absolute “no hidden code” assurance.
