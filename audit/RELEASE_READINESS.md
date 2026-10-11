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
| Candidate core and app regression tests | Pending CI |
| Candidate unsigned iOS Release build | Pending CI |
| Physical iPhone Keychain / Face ID / passcode / background and recovery checks | Not performed |
| Live-chain integration against a independently verified certificate and known node | Not performed on candidate |
| Apple Developer membership eligibility | Last recorded 2026-10-10: Individual team PPG9C98EY9 migration pending; current web session requires sign-in |
| Signed archive / provisioning / TestFlight processing | Not performed |
| App Store metadata, privacy disclosures and review submission | Not performed |

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
