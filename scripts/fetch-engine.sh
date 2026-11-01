#!/bin/bash
# Puts one native engine in iosApp/Frameworks after checking its sha256 against <name>-engine/ENGINE:
#   scripts/fetch-engine.sh lnd|ark|tor
# From the GitHub release named in ENGINE (release=, a plain download: no account or tool needed),
# else from the workflow run named in ENGINE (run=, needs the GitHub CLI `gh`; Actions artifacts
# expire after 90 days). To build an engine yourself on a Mac: ./<name>-engine/build-ios.sh, then
# unzip build/<name>/<Framework>.xcframework.zip into iosApp/Frameworks.
set -euo pipefail
cd "$(dirname "$0")/.."
name=${1:?lnd, ark or tor}
case "$name" in
  lnd) fw=Lndmobile; artifact=Lndmobile-xcframework ;;
  ark) fw=KilombinoArk; artifact=KilombinoArk-xcframework ;;
  tor) fw=KilombinoTor; artifact=KilombinoTor-xcframework ;;
  *) echo "unknown engine $name" >&2; exit 1 ;;
esac
ENGINE=$name-engine/ENGINE
pin() { grep "^$1=" "$ENGINE" | cut -d= -f2- || true; }
want=$(pin sha256); run=$(pin run); release=$(pin release)
if [ -z "$want" ]; then echo "$ENGINE has no sha256= yet" >&2; exit 1; fi
zip=$fw.xcframework.zip
dl=build/$name-fetch; rm -rf "$dl"; mkdir -p "$dl"
got=""
if [ -n "$release" ]; then
  url=https://github.com/Kilombino/kilowallet-ios/releases/download/$release/$zip
  if curl -fsSL --retry 3 -o "$dl/$zip" "$url"; then got=$(shasum -a 256 "$dl/$zip" | cut -d' ' -f1); from="release $release"; fi
fi
if [ "$got" != "$want" ] && [ -n "$run" ] && command -v gh >/dev/null; then
  rm -f "$dl/$zip"
  gh run download "$run" -R Kilombino/kilowallet-ios -n "$artifact" -D "$dl"
  got=$(shasum -a 256 "$dl/$zip" | cut -d' ' -f1); from="run $run"
fi
if [ "$got" != "$want" ]; then echo "$zip: sha256 ${got:-none}, $ENGINE says $want" >&2; exit 1; fi
rm -rf "iosApp/Frameworks/$fw.xcframework"; mkdir -p iosApp/Frameworks
unzip -q "$dl/$zip" -d iosApp/Frameworks
ls "iosApp/Frameworks/$fw.xcframework"
echo "$fw.xcframework $got ($from)"
