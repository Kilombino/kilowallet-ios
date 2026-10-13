#!/bin/bash
# Boots an iPhone simulator, installs the app and screenshots its main screens.
# Each screen is set up by the app itself in Debug builds with `-kwDemo <screen>` (a throwaway,
# in-memory "abandon … about" test wallet; the Keychain is never touched), and the app writes a
# marker file into its Documents folder when the screen has settled.
set -euo pipefail
APP="$1"; OUT="$2"; ONLY="${3:-}"
mkdir -p build
CHECKER="build/check-screen"
xcrun swiftc scripts/check-screen.swift -o "$CHECKER"
BUNDLE=$(/usr/libexec/PlistBuddy -c "Print CFBundleIdentifier" "$APP/Info.plist")
mkdir -p "$OUT" screenshots-logs

UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
d = json.load(sys.stdin)["devices"]
best = None
for rt, devs in d.items():
    if "iOS" not in rt: continue
    for x in devs:
        if x["name"].startswith("iPhone") and "Pro Max" not in x["name"] and "SE" not in x["name"]:
            best = (rt, x["name"], x["udid"])
print(best[2]); print(best[0], best[1], file=sys.stderr)
')
echo "simulator: $UDID"
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl status_bar "$UDID" override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3 || true
xcrun simctl install "$UDID" "$APP"

n=0
failures=0
for screen in welcome create restore watch home receive send review settings wallets history private note request export coins recipients backup filters units; do
  n=$((n + 1))
  if [ -n "$ONLY" ] && [ "$screen" != "$ONLY" ]; then continue; fi
  xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
  DATA=$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)
  rm -f "$DATA/Documents/kw-ready-$screen"
  xcrun simctl launch --console-pty "$UDID" "$BUNDLE" -kwDemo "$screen" > "screenshots-logs/$screen.log" 2>&1 &
  LP=$!
  limit=20; [ "$screen" = home ] && limit=150
  for i in $(seq 1 $limit); do
    [ -f "$DATA/Documents/kw-ready-$screen" ] && break
    sleep 1
  done
  if [ -f "$DATA/Documents/kw-ready-$screen" ]; then echo "$screen: ready after ${i}s"; else echo "$screen: NOT ready after ${limit}s"; exit 1; fi
  sleep 3
  SHOT="$OUT/$(printf %02d $n)-$screen.png"
  xcrun simctl io "$UDID" screenshot "$SHOT"
  expected=""
  case "$screen" in
    recipients) expected="Saved recipients" ;;
    backup) expected="Check written backup" ;;
    filters) expected="Search and filters" ;;
    units) expected="0.00250000" ;;
    coins) expected="Choose exact coins" ;;
    review) expected="Review payment" ;;
    wallets) expected="Wallets" ;;
    history) expected="Mining payout" ;;
    private) expected="Balance hidden" ;;
    note) expected="October test payout" ;;
    request) expected="Requested amount" ;;
    export) expected="Export history?" ;;
  esac
  if [ -n "$expected" ]; then
    check_args=("$SHOT" "$expected")
    [ "$screen" = request ] && check_args+=("?chain=xbt&amount=0.00120000")
    rendered=false
    for attempt in 1 2 3; do
      if "$CHECKER" "${check_args[@]}"; then rendered=true; break; fi
      sleep 3
      xcrun simctl io "$UDID" screenshot "$SHOT"
    done
    if [ "$rendered" != true ]; then
      echo "$screen: rendered screen did not match readiness marker"
      failures=$((failures + 1))
    else
      echo "$screen: visible title verified"
    fi
  fi
  kill $LP 2>/dev/null || true
done
xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
ls -la "$OUT"

# Preserve all screen evidence even if one OCR assertion fails.
[ "$failures" -eq 0 ]
