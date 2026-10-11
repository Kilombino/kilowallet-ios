# Kilowallet release readiness

Original app: **Kilombino** (https://github.com/Kilombino/kilowallet-ios, https://x.com/Kilombino_).
Distribution maintainer: romanmodin. User confirmed author permission to release on 2026-10-11.
Apache-2.0 LICENSE and original NOTICE retained. No private credentials belong in this repository.

## Candidate changes

- Require TLS for all endpoints; require explicit certificate fingerprint approval before any application request.
- Parse and validate BLAKE2b tip header proof of work; describe the remaining trust in the selected server accurately.
- Match broadcast receipts to locally calculated transaction IDs. Preserve the exact signed transaction before sending and retry that same transaction after uncertain delivery.
- Reject duplicate/invalid coins, overflow, nonfinite fees, malformed transactions, and ambiguous or incompatible payment URIs.
- Bound transport buffers, response deadlines, notification counts and address scanning; refresh individual payment proofs on every scan.
- Prevent concurrent unlock/send operations and stale callbacks after navigation, backgrounding or wallet removal.
- Preserve existing Keychain secrets when a new save fails; report deletion failures; mask passphrases and cover inactive app snapshots.
- Add Kilombino attribution in Settings and README; show XBT consistently in send/receive amounts.
- Add regression tests covering the security findings, authentication races, recovery data and failed storage.

## Gates

| Gate | Status |
| --- | --- |
| Source review at upstream e20e31e | Completed with findings; see separate baseline review |
| Baseline JVM and native characterization | Passed in Actions run 38139630578; passing reproductions confirm upstream defects |
| Candidate core and app regression tests | Final executable source 70afec7: 60 substantive executions passed (21 core + 9 app tests on each of JVM and iOS arm64 Simulator), zero failures; includes exact-transaction retry and restart coverage |
| Candidate unsigned iOS Release build | Passed at final 70afec7 in Actions run 38142184037; strict xcodebuild exit and executable-presence checks |
| Simulator launch and visual inspection | All nine principal screens reached readiness; screenshots inspected on iPhone 16 Plus / iOS 26.2; see evidence/simulator-screens.json |
| Physical iPhone Keychain / Face ID / passcode / background and recovery checks | Not performed |
| Live-chain integration against an independently verified certificate and known node | Not performed on candidate |
| Apple provisioning access | Browser sign-in restored 2026-10-11 about 13:33Z. Apple confirms membership benefits temporarily disabled while Individual-to-Organization migration awaits business website, phone and domain email. Kilowallet bundle identifier/app record not created. Earlier API 403 receipt retained as historical evidence. |
| Signed archive / provisioning / TestFlight processing | Not performed |
| App Store metadata and privacy information | Drafts prepared in distribution/app-store-metadata.json and PRIVACY.md; not entered in App Store Connect or submitted |

Do not interpret a passing automated build as completion of the remaining gates. This review is not a guarantee that all dependencies, runtime behavior or cryptography are free of defects.

## Device acceptance tests (disposable test wallet only)

1. With an iPhone passcode enabled, create a wallet, correctly confirm all requested words, and restore the same public test mnemonic plus passphrase. Check derived address against independently generated vectors. Verify incorrect passphrase opens a different wallet.
2. Cancel/deny Face ID and passcode prompts; verify neither secrets nor sends are released. Repeatedly tap Send and verify one authentication request. Background during unlock, and verify the stale callback cannot reveal or send.
3. Switch apps while viewing recovery words or restoring; the app switcher snapshot must be covered and secrets dismissed on return. Test screenshot/recording exposure separately; iOS does not provide a universal screenshot prohibition.
4. Attempt wallet creation when a Keychain wallet already exists and while protected data is unavailable. The old item must survive. Simulate a deletion failure: wallet metadata must remain available.
5. Reject the first server certificate, then independently verify and accept it. Rotate or mismatch the pin: no wallet query may be sent. TLS failure must never downgrade to plaintext, even for local nodes.
6. Exercise network loss before/after broadcast and app termination. The next send must recover the exact pending raw transaction and transaction ID, with no new payment created. Verify accepted/pending/confirmed state against an independently trusted node.
7. Check QR scanning with denied camera access, wrong-chain and malformed URIs, watch-only wallets, large text, small-screen layouts and accessibility labels.

A real-funds transaction requires a separate user-authorized financial action; automated tests use synthetic inputs and public test mnemonics only.

## Baseline finding disposition

| Finding | Candidate treatment / remaining limit |
| --- | --- |
| H1 TLS downgrade | Removed; regression tests reject fallback for public-looking and private addresses |
| H2 secret screens | Inactive-screen cover, background dismissal and masked passphrase implemented; physical iPhone snapshot/Face ID tests remain required |
| H3 stale operations | Epoch checks, single-flight authentication/signing and cancellation implemented; delayed callback tests passed |
| H4 server trust | Header format and PoW checked, stale proof cache removed, server trust disclosed. Full linked-header/best-work validation is still absent; this remains a server-trusting light wallet |
| M1 broadcast receipts | Locally matched IDs and persisted exact raw transaction; restart/retry regression added in final candidate |
| M2 bounds/arithmetic | Bounded frame/transaction/request/scan work, canonical counts, checked sums and duplicate detection; full fuzzing remains future work |
| M3 Keychain / secret lifetime | Save no longer deletes an existing item, delete errors propagate, setup serialized, private-node/HMAC buffers cleaned in finally; managed strings cannot be reliably zeroed |
| M4 initial certificate trust | Explicit fingerprint approval required before all application requests |
| M5 address progress | Receive index cannot move backward on this device; full issued/change address accounting and broader restore discovery remain future improvements |
| M6 build integrity | Independent release gate pins Actions, checks wrapper/distribution and fails on actual Xcode exit status. Full transitive source review and dependency locking remain outstanding |

The automated checks support continued beta preparation. They do not establish production release readiness, Apple acceptance, network propagation or physical-device behavior. No signing credential or real wallet seed has been committed to this repository.

## Recommended improvements before wider distribution

1. Have a second wallet/security engineer review transaction construction, recovery and the complete dependency tree. Current checks cover first-party code and direct advisory lookups, not an independent cryptographic audit.
2. Add linked-header/best-work verification and distinguish server-reported confirmations from independently verified inclusion throughout the UI. A valid individual header is not proof of the strongest chain.
3. Track every issued receive and change address and support explicit recovery look-ahead/account discovery. The current device-local monotonic receive index is narrower than complete invoice/address management.
4. Tighten the existing onboarding copy: restoration supports the implemented English BIP-39/BIP-84 account rather than every standard wallet; server operators can correlate queries, and an address may be reused until usage is discovered. Avoid absolute privacy claims.
5. Complete small-screen, large-text, VoiceOver, QR permission and hardware authentication checks. Nine simulator screenshots show initial rendering only; the send demo remains in coin-loading state without an approved server, and the Settings credit is below the first viewport.
6. Lock and review transitive dependencies, replace the inherited permissive iOS workflow with the strict release gate for ordinary PRs, and retain reproducible build provenance for the eventual signed binary.
