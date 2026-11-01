#!/bin/bash
# Puts the Tor engine (KilombinoTor.xcframework) in iosApp/Frameworks, from the tor-ios.yml run
# named in tor-engine/ENGINE, after checking its sha256. Needs the GitHub CLI (`gh`, logged in,
# or GH_TOKEN in CI). To build it yourself on a Mac instead: ./tor-engine/build-ios.sh, then
# unzip build/tor/KilombinoTor.xcframework.zip into iosApp/Frameworks.
set -euo pipefail
cd "$(dirname "$0")/.."
pin() { grep "^$1=" tor-engine/ENGINE | cut -d= -f2-; }
run=$(pin run); want=$(pin sha256)
if [ -z "$run" ] || [ -z "$want" ]; then echo "tor-engine/ENGINE has no run= / sha256= yet" >&2; exit 1; fi
dl=build/tor-fetch; rm -rf "$dl"; mkdir -p "$dl"
gh run download "$run" -R Kilombino/kilowallet-ios -n KilombinoTor-xcframework -D "$dl"
got=$(shasum -a 256 "$dl/KilombinoTor.xcframework.zip" | cut -d' ' -f1)
if [ "$got" != "$want" ]; then echo "KilombinoTor.xcframework.zip: sha256 $got, ENGINE says $want" >&2; exit 1; fi
rm -rf iosApp/Frameworks/KilombinoTor.xcframework; mkdir -p iosApp/Frameworks
unzip -q "$dl/KilombinoTor.xcframework.zip" -d iosApp/Frameworks
ls iosApp/Frameworks/KilombinoTor.xcframework
echo "KilombinoTor.xcframework $got (run $run)"
