#!/usr/bin/env python3
"""Copy the Android app's language tables into the iOS app's resources.

The Android app (Kilombino/kilowallet) translates by the English string itself: one JSON table
per language, app/src/main/assets/i18n/<lang>.json, keyed by the text as the code writes it (see
its ui/i18n/I18n.kt). The iOS app uses the same tables and the same translator
(app/src/commonMain/.../app/i18n/I18n.kt), so they are copied as they are, except:

  * entries that name the SHA-256 chain or the pools by their Android names ("spamchain",
    "spamcoin", "Poolcoins", "coinjoin", "mix"/"mixed" and their translations, in the key or the
    translation) are left out: those words must never reach the iOS UI (App Store naming: Corecoin,
    Kilojoin; pools, shared payments, equal outputs). The iOS screens for pools have their own texts;
  * scripts/i18n-ios/<lang>.json, if present, is merged on top: strings only iOS has.

Usage: scripts/sync-i18n.py [ANDROID_REPO] [REV]     (defaults: ~/Projects/blake2b/kilowallet, HEAD)
"""
import json, os, re, subprocess, sys

LANGS = ["es", "ca", "de", "fr", "it", "pt", "zh"]
# The same list as app/src/desktopTest/.../I18nTest.kt (BANNED there): the words and their translations.
BANNED = re.compile(r"spam|coin ?join|poolcoin|poolsat|mix|mezcl|barrej|barreg|m[ée]lang|mescol|misch|mistur|混合|混币|混幣|垃圾", re.IGNORECASE)

here = os.path.dirname(os.path.abspath(__file__))
repo = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Projects/blake2b/kilowallet")
rev = sys.argv[2] if len(sys.argv) > 2 else "HEAD"
out_dir = os.path.join(here, "..", "app", "src", "commonMain", "composeResources", "files", "i18n")
os.makedirs(out_dir, exist_ok=True)
commit = subprocess.check_output(["git", "-C", repo, "rev-parse", "--short", rev], text=True).strip()

for lang in LANGS:
    raw = subprocess.check_output(["git", "-C", repo, "show", f"{rev}:app/src/main/assets/i18n/{lang}.json"])
    table = json.loads(raw)
    kept = {k: v for k, v in table.items() if not BANNED.search(k) and not BANNED.search(v)}
    extra_path = os.path.join(here, "i18n-ios", f"{lang}.json")
    extra = {}
    if os.path.exists(extra_path):
        with open(extra_path, encoding="utf-8") as f:
            extra = json.load(f)
        bad = [k for k, v in extra.items() if BANNED.search(k) or BANNED.search(v)]
        if bad:
            sys.exit(f"{extra_path}: banned words in {bad}")
        kept.update(extra)
    with open(os.path.join(out_dir, f"{lang}.json"), "w", encoding="utf-8") as f:
        json.dump(kept, f, ensure_ascii=False, indent=0)
        f.write("\n")
    print(f"{lang}: {len(table)} from Android {commit}, {len(table) - (len(kept) - len(extra))} left out, {len(extra)} iOS-only")
