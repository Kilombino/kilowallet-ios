#!/bin/bash
# Builds the Tor engine for iOS as tor-engine/ENGINE says: KilombinoTor.xcframework (device arm64,
# simulator arm64 + x86_64), a DYNAMIC framework (KilombinoTor.framework, embedded in the app) with
# kilombino_tor.h and a module map, so Swift can `import KilombinoTor`. Needs macOS with Xcode and rustup.
#
# The Rust crate (tor-engine/kilombino-tor) reaches arti by path in tor-engine/arti, checked out
# here at the pinned commit; its Cargo.lock comes from arti's own, so every crate is the version
# Kilowallet Android's libarti.so uses.
#
# Why dynamic: the app also links the Ark engine's Rust static library. A second Rust library in
# the same binary brings a second rust_eh_personality, and with Swift's C++ and Objective-C ones
# that is four personality routines, more than compact unwind can encode: Xcode 26's linker stops
# ("Too many personality routines"). Turning compact unwind off links, but then Kotlin/Native's
# exceptions (C++ unwinding) find no unwind info and the app dies on the first one. In its own
# dylib, Tor's Rust personality lives apart, as the linker's message suggests.
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
# Rust's prebuilt compiler_builtins carry embedded bitcode of rustc's LLVM (22), newer than Xcode's:
# nm would try to read it and stop (the linker only takes the machine code next to it, as it does
# for the Ark engine). The checks read the machine code only.
NM="xcrun nm --no-llvm-bc"
# The install name the app's loader looks for: inside the app's Frameworks folder.
for t in $targets; do
  time cargo rustc --locked --profile release-small --lib --target "$t" --crate-type cdylib -- \
    -C link-arg=-Wl,-install_name,@rpath/KilombinoTor.framework/KilombinoTor
done
lib() { echo "$CARGO_TARGET_DIR/$1/release-small/libkilombino_tor.dylib"; }
framework() { # dir binary platform(iPhoneOS|iPhoneSimulator)
  local f=$1/KilombinoTor.framework
  mkdir -p "$f/Headers" "$f/Modules"
  cp "$2" "$f/KilombinoTor"
  cp "$crate/include/kilombino_tor.h" "$f/Headers/"
  cat > "$f/Modules/module.modulemap" <<'MAP'
framework module KilombinoTor {
    umbrella header "kilombino_tor.h"
    export *
}
MAP
  cat > "$f/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key><string>en</string>
	<key>CFBundleExecutable</key><string>KilombinoTor</string>
	<key>CFBundleIdentifier</key><string>com.kilombino.kilowallet.tor</string>
	<key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
	<key>CFBundleName</key><string>KilombinoTor</string>
	<key>CFBundlePackageType</key><string>FMWK</string>
	<key>CFBundleShortVersionString</key><string>2.7.0</string>
	<key>CFBundleVersion</key><string>1</string>
	<key>CFBundleSupportedPlatforms</key><array><string>$3</string></array>
	<key>MinimumOSVersion</key><string>$IPHONEOS_DEPLOYMENT_TARGET</string>
</dict>
</plist>
PLIST
}
mkdir -p "$out/dev" "$out/sim"
framework "$out/dev" "$(lib aarch64-apple-ios)" iPhoneOS
lipo -create "$(lib aarch64-apple-ios-sim)" "$(lib x86_64-apple-ios)" -output "$out/sim-KilombinoTor"
framework "$out/sim" "$out/sim-KilombinoTor" iPhoneSimulator
cd "$out"
xcodebuild -create-xcframework -framework dev/KilombinoTor.framework -framework sim/KilombinoTor.framework \
  -output KilombinoTor.xcframework
find KilombinoTor.xcframework | sort
du -sh KilombinoTor.xcframework KilombinoTor.xcframework/*
otool -L KilombinoTor.xcframework/ios-arm64/KilombinoTor.framework/KilombinoTor
# Exported: the four C functions (a cdylib exports only #[no_mangle] ones).
{ $NM -gU KilombinoTor.xcframework/ios-arm64/KilombinoTor.framework/KilombinoTor 2>&1 || true; } > exported.txt
cat exported.txt | head -20
test "$(grep -c ' _kilombino_tor_' exported.txt || true)" = 4
# A stable zip (sorted entries, fixed times) so the hash depends on the contents only.
find KilombinoTor.xcframework -exec touch -h -t 202601010000 {} +
find KilombinoTor.xcframework \( -type f -o -type l \) | LC_ALL=C sort | zip -q -X -y -9 KilombinoTor.xcframework.zip -@
shasum -a 256 KilombinoTor.xcframework.zip | tee KilombinoTor.xcframework.zip.sha256
