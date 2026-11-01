#!/bin/bash
# Puts the Lightning engine (Lndmobile.xcframework) in iosApp/Frameworks, from the lnd-ios.yml run
# named in lnd-engine/ENGINE, after checking its sha256. Needs the GitHub CLI (`gh`, logged in,
# or GH_TOKEN in CI). To build it yourself on a Mac instead: ./lnd-engine/build-ios.sh, then
# unzip build/lnd/Lndmobile.xcframework.zip into iosApp/Frameworks.
set -euo pipefail
cd "$(dirname "$0")/.."
pin() { grep "^$1=" lnd-engine/ENGINE | cut -d= -f2-; }
run=$(pin run); want=$(pin sha256)
if [ -z "$run" ] || [ -z "$want" ]; then echo "lnd-engine/ENGINE has no run= / sha256= yet" >&2; exit 1; fi
dl=build/lnd-fetch; rm -rf "$dl"; mkdir -p "$dl"
gh run download "$run" -R Kilombino/kilowallet-ios -n Lndmobile-xcframework -D "$dl"
got=$(shasum -a 256 "$dl/Lndmobile.xcframework.zip" | cut -d' ' -f1)
if [ "$got" != "$want" ]; then echo "Lndmobile.xcframework.zip: sha256 $got, ENGINE says $want" >&2; exit 1; fi
rm -rf iosApp/Frameworks/Lndmobile.xcframework; mkdir -p iosApp/Frameworks
unzip -q "$dl/Lndmobile.xcframework.zip" -d iosApp/Frameworks
ls iosApp/Frameworks/Lndmobile.xcframework
echo "Lndmobile.xcframework $got (run $run)"
