#!/bin/bash
# Boots an iPhone simulator, installs the app and screenshots its main screens.
# Each screen is set up by the app itself in Debug builds with KW_DEMO=<screen> (a throwaway,
# in-memory "abandon … about" test wallet; the Keychain is never touched), and the app writes a
# marker file into its Documents folder when the screen has settled.
set -euo pipefail
APP="$1"; OUT="$2"
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
# "screen@lang" shows that screen in another language (the app's language picker, set by the demo).
for screen in welcome create restore watch home simple lightning ln-starting ln-running ln-failed ark locked receive send review replace settings pool-intro pools pool-join pool-create pool-pay pool-round home@es receive@es settings@es lightning@es ln-running@es pools@es pool-round@zh home@zh dice backup cpfp receive-taproot settings-taproot pool-history psbt immature dice@es backup@es pool-history@es psbt@es ark-ready ark-ready@es ark@es ln-bridge ln-move settings-notify ln-bridge@es ark-live tor-live tor-log ln-live; do
  n=$((n + 1))
  base=${screen%@*}
  name=${screen/@/-}
  xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
  DATA=$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)
  rm -f "$DATA/Documents/kw-ready-$base"
  SIMCTL_CHILD_KW_DEMO="$screen" xcrun simctl launch --console-pty "$UDID" "$BUNDLE" > "screenshots-logs/$name.log" 2>&1 &
  LP=$!
  limit=20; { [ "$base" = home ] || [ "$base" = simple ] || [ "$base" = immature ]; } && limit=150
  case "$base" in pool*) limit=90 ;; esac
  # The real Lightning engine: LND starts, makes a throwaway wallet and syncs with the chain's peers.
  # Tor first (up to 3 min), then the node, the watchtower and Kilombino's node by its onion.
  [ "$base" = ln-live ] && limit=1300
  # The real Tor (Arti in the app): it bootstraps, then the Electrum server is reached through it.
  { [ "$base" = tor-live ] || [ "$base" = tor-log ]; } && limit=420
  # The real Ark engine: it starts, makes a throwaway wallet on the Ark server and hands out an address.
  [ "$base" = ark-live ] && limit=300
  for i in $(seq 1 $limit); do
    [ -f "$DATA/Documents/kw-ready-$base" ] && break
    sleep 1
  done
  if [ -f "$DATA/Documents/kw-ready-$base" ]; then echo "$screen: ready after ${i}s"; else echo "$screen: NOT ready after ${limit}s (screenshot anyway)"; fi
  sleep 3
  xcrun simctl io "$UDID" screenshot "$OUT/$(printf %02d $n)-$name.png"
  if [ "$base" = ln-live ]; then
    LOG="$DATA/Library/Application Support/lnd/logs/bitcoin/mainnet/lnd.log"
    [ -f "$LOG" ] && cp "$LOG" screenshots-logs/lnd-live.log && tail -25 screenshots-logs/lnd-live.log || echo "no LND log at $LOG"
    ls -la "$DATA/Library/Application Support/lnd" || true
  fi
  case "$base" in tor-live|tor-log|ln-live)
    TLOG="$DATA/Library/Application Support/tor.log"
    [ -f "$TLOG" ] && cp "$TLOG" "screenshots-logs/tor-$name.log" && tail -15 "screenshots-logs/tor-$name.log" || echo "no Tor log at $TLOG"
    grep "KW " "screenshots-logs/$name.log" || true ;;
  esac
  if [ "$base" = ark-live ]; then
    ALOG="$DATA/Library/Application Support/ark/debug.log"
    [ -f "$ALOG" ] && cp "$ALOG" screenshots-logs/ark-live.log && grep -viE 'mnemonic|seed|passphrase' screenshots-logs/ark-live.log | tail -25 || echo "no Ark log at $ALOG"
    ls -la "$DATA/Library/Application Support/ark" || true
  fi
  kill $LP 2>/dev/null || true
done
xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
ls -la "$OUT"
