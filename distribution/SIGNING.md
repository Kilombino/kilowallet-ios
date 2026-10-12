# Kilowallet signing and TestFlight handoff

Created by **Kilombino**. The Apache-2.0 LICENSE and NOTICE remain in the distribution.

## Current blocker — 2026-10-12

The authenticated Apple Developer account for Roman Winter (team PPG9C98EY9) still says the individual-to-organization migration is processing and membership benefits are temporarily disabled. No signed Kilowallet archive, provisioning profile, or TestFlight upload has been verified. The repository currently has no signing secrets configured.

## When provisioning becomes available

1. Verify the final organization and team ID in Apple Developer. Use that team throughout. Register the explicit bundle identifier `com.kilombino.kilowallet` if it is still absent, then create the matching Kilowallet App Store Connect record. Do not reuse another app's bundle ID.
2. On an authorized Mac, install Xcode and JDK 25 and sign into the same developer account in Xcode. Let Xcode manage the certificate/profile. Keep passwords, API keys and signing private keys out of this repo, chat and build logs.
3. Check out the reviewed commit. Choose a new positive build number and run:

   ```sh
   TEAM_ID=PPG9C98EY9 BUILD_NUMBER=1 bash scripts/archive-release.sh
   ```

   Replace the team ID if Apple changes it during migration. Increment the build number if it has already been uploaded. The script reruns core/app JVM and iOS tests, archives Release, verifies the archived signature/team/bundle/build, and exports an App Store Connect IPA. It records the source commit and IPA SHA-256. It does not upload or accept agreements. The script has only been syntax checked so far; the Apple-dependent archive/export steps are unverified.
4. Complete the physical-device checks in `audit/RELEASE_READINESS.md`. Confirm the encryption/export declaration for the bundled implementation and review the privacy answers. The inherited `ITSAppUsesNonExemptEncryption = NO` flag is not evidence of a completed classification review.
5. Open the signed archive in Xcode Organizer, validate and upload to App Store Connect. Alternatively use Transporter with the exported IPA. Verify Apple's processing result and the exact version/build/commit before assigning it to internal TestFlight testers. External testing may require beta review. TestFlight processing is separate from App Store publication.

## Prepared release materials

- `distribution/app-store-metadata.json`: draft description, creator credit and beta testing notes.
- `PRIVACY.md`: data storage and server-disclosure information.
- `audit/RELEASE_READINESS.md`: verified evidence and outstanding device/network checks.
- GitHub Actions `iOS`: core/app JVM and iOS tests, strict unsigned device Release, simulator build and screenshots.

Unsigned simulator/device builds cannot be installed through TestFlight. Passing automated tests does not establish hardware authentication, network propagation, or Apple's acceptance.

Apple references: [distribution](https://developer.apple.com/documentation/xcode/distributing-your-app-for-beta-testing-and-releases), [archive export](https://help.apple.com/xcode/mac/current/en.lproj/dev23ea8b877.html), [export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance).
