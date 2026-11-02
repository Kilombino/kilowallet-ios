#!/bin/bash
# Binds LND for iOS as lnd-engine/ENGINE says (Lightning Fork 21.2 since 2 Nov 2026). Needs macOS
# with Xcode and the pinned Go.
set -euo pipefail
cd "$(dirname "$0")/.."
ENGINE=$PWD/lnd-engine/ENGINE
pin() { grep "^$1=" "$ENGINE" | cut -d= -f2-; }
out=$PWD/build/lnd; rm -rf "$out"; mkdir -p "$out"
src=$out/src
git clone --quiet --filter=blob:none "$(pin repo)" "$src"
git -C "$src" checkout --quiet "$(pin commit)"
test "$(git -C "$src" rev-parse HEAD)" = "$(pin commit)"
ls "$src"/mobile/*_generated.go >/dev/null
export GOFLAGS=-mod=mod GOTOOLCHAIN=local PATH="$(go env GOPATH)/bin:$PATH"
go version
go install "golang.org/x/mobile/cmd/gomobile@$(pin gomobile)"
go install "golang.org/x/mobile/cmd/gobind@$(pin gomobile)"
gomobile init
tags=$(pin tags); iosversion=$(pin iosversion)
test -n "$tags" && test -n "$iosversion"
cd "$src"
time gomobile bind -target=ios,iossimulator -iosversion "$iosversion" -tags="$tags" \
  -ldflags "-s -w" -v -o "$out/Lndmobile.xcframework" ./mobile
cd "$out"
find Lndmobile.xcframework -maxdepth 2 | sort
du -sh Lndmobile.xcframework Lndmobile.xcframework/*
# A stable zip (sorted entries, fixed times) so the hash depends on the contents only.
find Lndmobile.xcframework -exec touch -h -t 202601010000 {} +
find Lndmobile.xcframework \( -type f -o -type l \) | LC_ALL=C sort | zip -q -X -y -9 Lndmobile.xcframework.zip -@
shasum -a 256 Lndmobile.xcframework.zip | tee Lndmobile.xcframework.zip.sha256
ls -la Lndmobile.xcframework.zip
