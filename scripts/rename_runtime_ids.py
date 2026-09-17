#!/usr/bin/env python3
"""rename_runtime_ids.py - Stage 4: minis_* / minis-* runtime identifiers -> jarvis_*.

Replaces lowercase runtime identifiers that carry the `minis` brand in:
  - SharedPreferences keys:        minis_alarms, minis_config_enabled, ...
  - Notification channel IDs:     minis_agent_notifications, minis_alarm_group
  - File/log names:                minis-logs-, minis-update.apk, minis-global
  - HTTP headers:                  X-Minis-Token, x-minis-token
  - Env vars:                      MINIS_DEBUG_TOKEN, MINIS_NOFF_DEBUG, MINIS_CHAT_SESSION_ID
  - JSON-RPC method names:         debug.minisConfig.exec, permissions.minisConfig.enabled
  - Shell sentinel markers:        __minis__, __MINIS_DONE_
  - Android string resource keys:  about_minis_tagline, browser_minis_browsing, ...

Replacements are prefix-based: `minis_` -> `jarvis_`, `minis-` -> `jarvis-`,
`MINIS_` -> `JARVIS_`, `Minis_` -> `Jarvis_` (only when followed by lowercase
word chars, so CamelCase code symbols like MinisApp are NOT touched).

NOT changed: CamelCase code symbols (MinisApp, MinisAccessibilityService — Layer 5,
deferred), third-party model names (ministral, mistral.ministral — Mistral AI).
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

TARGETS_GLOBS = [
    "src/android/**/*.kt",
    "src/android/**/*.java",
    "src/android/**/*.xml",
    "src/android/**/*.py",
    "src/android/**/*.sh",
    "src/android/**/*.json",
    "src/android/**/*.properties",
]

EXCLUDE_DIRS = {"build", ".gradle", ".cxx", ".tmp_deb", "Minis.xcodeproj"}

# Token-level replacements. Each is a full-token replace, safe because these
# are distinctive prefixed identifiers. Ordered so longer tokens win.
REPLACEMENTS = [
    # HTTP headers (case variants)
    ("X-Minis-Token", "X-Jarvis-Token"),
    ("x-minis-token", "x-jarvis-token"),
    # Env vars (UPPERCASE)
    ("MINIS_DEBUG_TOKEN", "JARVIS_DEBUG_TOKEN"),
    ("MINIS_NOFF_DEBUG", "JARVIS_NOFF_DEBUG"),
    ("MINIS_CHAT_SESSION_ID", "JARVIS_CHAT_SESSION_ID"),
    ("__MINIS_DONE_", "__JARVIS_DONE_"),
    # Shell sentinels
    ("__minis__", "__jarvis__"),
    # JSON-RPC method segments (camelCase, lowercase-first)
    ("minisConfig", "jarvisConfig"),
    # lowercase prefixes: minis_ / minis-  (but NOT ministral)
    # We handle these with a regex below, not plain replace, to avoid
    # hitting `ministral` (Mistral AI model name).
]

# Regex for the prefix substitutions. `minis_` and `minis-` followed by a
# word char, but NOT `ministral` (which is `ministr` + `al`).
PREFIX_RE = re.compile(r"minis([_-])(?![a-z]*ral)(?=\w)")
MINIS_UPPER_RE = re.compile(r"MINIS_([A-Z])")
MINIS_TITLE_RE = re.compile(r"\bMinis_([a-z])")  # Minis_xxx -> Jarvis_xxx (string keys)


def rewrite(text: str) -> str:
    # Full-token replacements first.
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    # Prefix replacements (regex, avoids ministral).
    text = PREFIX_RE.sub(r"jarvis\1", text)
    text = MINIS_UPPER_RE.sub(r"JARVIS_\1", text)
    text = MINIS_TITLE_RE.sub(r"Jarvis_\1", text)
    return text


def collect_files() -> list[Path]:
    out: list[Path] = []
    seen: set[Path] = set()
    for pat in TARGETS_GLOBS:
        for p in ROOT.glob(pat):
            if not p.is_file() or p in seen:
                continue
            if any(part in EXCLUDE_DIRS for part in p.parts):
                continue
            seen.add(p)
            out.append(p)
    return sorted(out)


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apply", action="store_true", help="write changes (default: dry run)")
    args = ap.parse_args()

    changed = 0
    errored = 0
    for p in collect_files():
        try:
            original = p.read_text(encoding="utf-8")
        except OSError as e:
            print(f"skip {p}: {e}", file=sys.stderr)
            continue
        try:
            new = rewrite(original)
        except Exception as e:
            errored += 1
            print(f"ERROR rewriting {p}: {e}", file=sys.stderr)
            continue
        if new == original:
            continue
        changed += 1
        if args.apply:
            try:
                p.write_text(new, encoding="utf-8")
            except OSError as e:
                errored += 1
                print(f"ERROR writing {p}: {e}", file=sys.stderr)
                continue
        try:
            print(f"{'WROTE' if args.apply else 'DRY  '} {p.relative_to(ROOT)}")
        except Exception:
            pass
    print(f"\n{'Wrote' if args.apply else 'Would change'} {changed} file(s); {errored} error(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())

