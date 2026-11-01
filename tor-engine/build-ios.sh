#!/bin/bash
# Builds the Tor engine for iOS as tor-engine/ENGINE says: KilombinoTor.xcframework (device arm64,
# simulator arm64 + x86_64), a static library with kilombino_tor.h and a module map, so Swift can
# `import KilombinoTor`. Needs macOS with Xcode and rustup.
#
# The Rust crate (tor-engine/kilombino-tor) reaches arti by path in tor-engine/arti, checked out
# here at the pinned commit; its Cargo.lock comes from arti's own, so every crate is the version
# Kilowallet Android's libarti.so uses.
#
# The app links another Rust library (the Ark engine), and two Rust static libraries both carry
# Rust's standard library and shared crates under the same names. So each slice is first linked
# into one object that exports only the kilombino_tor_* functions (everything else becomes local,
# as Xcode's "master object file" does), and that object is the library.
set -euo pipefail
cd "$(dirname "$0")/.."
ENGINE=$PWD/tor-engine/ENGINE
pin() { grep "^$1=" "$ENGINE" | cut -d= -f2-; }
crate=$PWD/tor-engine/kilombino-tor
out=$PWD/build/tor; rm -rf "$out"; mkdir -p "$out"
arti=$PWD/tor-engine/arti
if [ ! -e "$arti" ]; then
  git clone --quiet --filter=blob:none "$(pin repo)" "$arti"
fi
git -C "$arti" checkout --quiet "$(pin commit)"
test "$(git -C "$arti" rev-parse HEAD)" = "$(pin commit)"
rustup toolchain install "$(pin rustc)" --profile minimal
cd "$crate"
rustup override set "$(pin rustc)"
rustc --version
export IPHONEOS_DEPLOYMENT_TARGET="$(pin iosversion)"
export RUSTFLAGS="--remap-path-prefix=$PWD=/src --remap-path-prefix=$arti=/arti --remap-path-prefix=$HOME/.cargo=/cargo --remap-path-prefix=$HOME/.rustup=/rustup"
export CARGO_TARGET_DIR=$out/target
targets="aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios"
rustup target add $targets
for t in $targets; do
  time cargo rustc --locked --profile release-small --lib --target "$t" --crate-type staticlib
done
# One object per slice that exports only the C functions.
hide() { # target arch platform out
  local a=$CARGO_TARGET_DIR/$1/release-small/libkilombino_tor.a
  local sdk; sdk=$( [ "$3" = ios ] && echo iphoneos || echo iphonesimulator )
  xcrun --sdk "$sdk" ld -r -arch "$2" -platform_version "$3" "$IPHONEOS_DEPLOYMENT_TARGET" "$(xcrun --sdk "$sdk" --show-sdk-version)" \
    -S -exported_symbols_list "$crate/exported_symbols.txt" -force_load "$a" -o "$out/$1-bc.o"
  # Rust's own prebuilt pieces (compiler_builtins) carry bitcode of rustc's LLVM, newer than
  # Xcode's: its tools stop on it. The machine code is all the app links, so the bitcode goes.
  xcrun bitcode_strip -r "$out/$1-bc.o" -o "$out/$1.o"
  echo "== $1: the C functions before and after hiding the rest"
  { nm -gU "$a" 2>&1 || true; } | grep -i 'kilombino_tor_\|error' | grep -v '_R' | sort -u | head -20 || true
  { nm -m "$out/$1.o" 2>&1 || true; } | grep -i 'kilombino_tor_\|error' | grep -v '_R' | head -20 || true
  ar -t "$a" | head -5 || true
  ls -la "$a" "$out/$1.o"
  xcrun libtool -static -o "$4" "$out/$1.o" 2>&1 | grep -v "has no symbols" || true
}
mkdir -p "$out/dev" "$out/sim" "$out/headers"
hide aarch64-apple-ios arm64 ios "$out/dev/libkilombino_tor.a"
hide aarch64-apple-ios-sim arm64 ios-simulator "$out/sim-arm64.a"
hide x86_64-apple-ios x86_64 ios-simulator "$out/sim-x86_64.a"
lipo -create "$out/sim-arm64.a" "$out/sim-x86_64.a" -output "$out/sim/libkilombino_tor.a"
cp "$crate/include/kilombino_tor.h" "$crate/include/module.modulemap" "$out/headers/"
cd "$out"
xcodebuild -create-xcframework \
  -library dev/libkilombino_tor.a -headers headers \
  -library sim/libkilombino_tor.a -headers headers \
  -output KilombinoTor.xcframework
find KilombinoTor.xcframework | sort
du -sh KilombinoTor.xcframework KilombinoTor.xcframework/*
# Only the four C functions are left global (nm exits non-zero on odd members: its output counts).
{ nm -gU KilombinoTor.xcframework/ios-arm64/libkilombino_tor.a 2>&1 || true; } > exported.txt
grep -v '^$' exported.txt | head -40 || true
test "$(grep -c ' _kilombino_tor_' exported.txt || true)" = 4
if grep -E '^[0-9a-f]+ [A-Z] ' exported.txt | grep -v ' _kilombino_tor_'; then echo "warning: more global symbols than the four" >&2; fi
# A stable zip (sorted entries, fixed times) so the hash depends on the contents only.
find KilombinoTor.xcframework -exec touch -h -t 202601010000 {} +
find KilombinoTor.xcframework \( -type f -o -type l \) | LC_ALL=C sort | zip -q -X -y -9 KilombinoTor.xcframework.zip -@
shasum -a 256 KilombinoTor.xcframework.zip | tee KilombinoTor.xcframework.zip.sha256
