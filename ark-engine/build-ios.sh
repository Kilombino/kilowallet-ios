#!/bin/bash
# Builds the Ark engine for iOS as ark-engine/ENGINE says. Needs macOS with Xcode, rustup and protoc.
set -euo pipefail
cd "$(dirname "$0")/.."
ENGINE=$PWD/ark-engine/ENGINE
pin() { grep "^$1=" "$ENGINE" | cut -d= -f2-; }
out=$PWD/build/ark; rm -rf "$out"; mkdir -p "$out"
src=$out/src
git clone --quiet --filter=blob:none "$(pin repo)" "$src"
git -C "$src" checkout --quiet "$(pin commit)"
test "$(git -C "$src" rev-parse HEAD)" = "$(pin commit)"
rustup toolchain install "$(pin rustc)" --profile minimal
cd "$src"
rustup override set "$(pin rustc)"
rustc --version
IPHONEOS_DEPLOYMENT_TARGET="$(pin iosversion)" time ./"$(pin build)"
cp target/ios/KilombinoArk.xcframework.zip target/ios/KilombinoArk.xcframework.zip.sha256 "$out/"
ls -la "$out"
