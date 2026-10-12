#!/bin/bash
# Run on the authorized Mac after Apple restores provisioning access.
# Uses Xcode's signed-in developer account; never embeds signing credentials.
set -euo pipefail

if [[ "$(uname -s)" != Darwin ]]; then echo 'A Mac with Xcode is required.' >&2; exit 1; fi
: "${TEAM_ID:?Set TEAM_ID to the verified Apple developer team}"
: "${BUILD_NUMBER:?Set BUILD_NUMBER to a new positive integer for this upload}"
[[ "$TEAM_ID" =~ ^[A-Z0-9]{10}$ ]] || { echo 'Invalid team ID.' >&2; exit 1; }
[[ "$BUILD_NUMBER" =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid build number.' >&2; exit 1; }
cd "$(dirname "$0")/.."
[[ -z "$(git status --porcelain)" ]] || { echo 'Commit or preserve local changes before archiving.' >&2; exit 1; }
REV=$(git rev-parse HEAD)
OUT="$PWD/build/release-${BUILD_NUMBER}-${REV:0:12}"
[[ ! -e "$OUT" ]] || { echo 'This output directory already exists; preserve it and use a new build number.' >&2; exit 1; }
mkdir -p "$OUT"
xcodebuild -version > "$OUT/toolchain.txt"
java -version 2>> "$OUT/toolchain.txt"
./gradlew :core:jvmTest :core:iosSimulatorArm64Test :app:desktopTest :app:iosSimulatorArm64Test --stacktrace > "$OUT/tests.log" 2>&1
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$OUT/Kilowallet.xcarchive" \
  -allowProvisioningUpdates TEAM_ID="$TEAM_ID" DEVELOPMENT_TEAM="$TEAM_ID" \
  BUNDLE_ID=com.kilombino.kilowallet CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
  CODE_SIGN_STYLE=Automatic archive > "$OUT/archive.log" 2>&1

APP="$OUT/Kilowallet.xcarchive/Products/Applications/Kilowallet.app"
test -x "$APP/Kilowallet"
codesign --verify --deep --strict "$APP"
codesign -d --verbose=4 "$APP" 2> "$OUT/signature.txt"
grep -Fx "TeamIdentifier=$TEAM_ID" "$OUT/signature.txt" > /dev/null
[[ "$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")" = com.kilombino.kilowallet ]]
[[ "$(/usr/libexec/PlistBuddy -c 'Print CFBundleVersion' "$APP/Info.plist")" = "$BUILD_NUMBER" ]]

python3 - "$OUT" "$TEAM_ID" "$REV" "$BUILD_NUMBER" <<'PY'
import json, pathlib, plistlib, sys
out, team, revision, build = sys.argv[1:]
p = pathlib.Path(out)
options = dict(method='app-store-connect', destination='export', teamID=team,
               signingStyle='automatic', manageAppVersionAndBuildNumber=False,
               uploadSymbols=True)
(p / 'ExportOptions.plist').write_bytes(plistlib.dumps(options))
(p / 'provenance.json').write_text(json.dumps(dict(commit=revision, team=team,
    bundle='com.kilombino.kilowallet', build=build, uploaded=False), indent=2) + '\n')
PY
xcodebuild -exportArchive -archivePath "$OUT/Kilowallet.xcarchive" \
  -exportPath "$OUT/export" -exportOptionsPlist "$OUT/ExportOptions.plist" \
  -allowProvisioningUpdates > "$OUT/export.log" 2>&1
test -f "$OUT/export/Kilowallet.ipa"
unzip -q "$OUT/export/Kilowallet.ipa" -d "$OUT/export-verification"
EXPORTED_APP="$OUT/export-verification/Payload/Kilowallet.app"
codesign --verify --deep --strict "$EXPORTED_APP"
codesign -d --verbose=4 "$EXPORTED_APP" 2> "$OUT/exported-signature.txt"
grep -Fx "TeamIdentifier=$TEAM_ID" "$OUT/exported-signature.txt" > /dev/null
[[ "$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$EXPORTED_APP/Info.plist")" = com.kilombino.kilowallet ]]
[[ "$(/usr/libexec/PlistBuddy -c 'Print CFBundleVersion' "$EXPORTED_APP/Info.plist")" = "$BUILD_NUMBER" ]]
shasum -a 256 "$OUT/export/Kilowallet.ipa" > "$OUT/ipa.sha256"
echo "Signed archive and exported IPA: $OUT"
echo 'Not uploaded. Complete device checks and Apple compliance review before TestFlight distribution.'
