#!/bin/bash
# Puts the Ark engine (KilombinoArk.xcframework) in iosApp/Frameworks, from the ark-ios.yml run
# named in ark-engine/ENGINE, after checking its sha256. Needs the GitHub CLI (`gh`, logged in,
# or GH_TOKEN in CI). To build it yourself on a Mac instead: ./ark-engine/build-ios.sh, then
# unzip build/ark/KilombinoArk.xcframework.zip into iosApp/Frameworks.
set -euo pipefail
cd "$(dirname "$0")/.."
pin() { grep "^$1=" ark-engine/ENGINE | cut -d= -f2-; }
run=$(pin run); want=$(pin sha256)
if [ -z "$run" ] || [ -z "$want" ]; then echo "ark-engine/ENGINE has no run= / sha256= yet" >&2; exit 1; fi
dl=build/ark-fetch; rm -rf "$dl"; mkdir -p "$dl"
gh run download "$run" -R Kilombino/kilowallet-ios -n KilombinoArk-xcframework -D "$dl"
got=$(shasum -a 256 "$dl/KilombinoArk.xcframework.zip" | cut -d' ' -f1)
if [ "$got" != "$want" ]; then echo "KilombinoArk.xcframework.zip: sha256 $got, ENGINE says $want" >&2; exit 1; fi
rm -rf iosApp/Frameworks/KilombinoArk.xcframework; mkdir -p iosApp/Frameworks
unzip -q "$dl/KilombinoArk.xcframework.zip" -d iosApp/Frameworks
ls iosApp/Frameworks/KilombinoArk.xcframework
echo "KilombinoArk.xcframework $got (run $run)"
