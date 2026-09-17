#!/usr/bin/env python3
"""rename_offload_cli.py - Stage 2.3: minis-* offload CLI names -> jarvis-*.

Replaces the agent-facing offload CLI command names everywhere they appear as
string literals (registration, help text, system prompts, skill docs). Also
renames the `jarvis-mcp-cli` tool directory and the `jarvis-open` browser script.

Replacements (longest-first to avoid prefix collisions):
  jarvis-mcp-cli      -> jarvis-mcp-cli   (CLI name + dir name)
  jarvis-mcp-daemon   -> jarvis-mcp-daemon
  jarvis-sessions-cli -> jarvis-sessions-cli
  jarvis-browser-use  -> jarvis-browser-use
  jarvis-model-use    -> jarvis-model-use
  jarvis-scheduled    -> jarvis-scheduled
  jarvis-config       -> jarvis-config
  jarvis-debug        -> jarvis-debug
  jarvis-open         -> jarvis-open

Also: jarvis.db -> jarvis.db (Android database filename).

NOT changed: code symbols (MinisOpenUrlBroker etc, Layer 5), com.openminis
(Bundle ID, Layer 4), root@minis (PS1, already done in 2.2).

Directory rename (jarvis-mcp-cli -> jarvis-mcp-cli) is done by --apply-rename
which moves the filesystem directory after the string rewrite. This must run
BEFORE the next build or Gradle won't find the renamed tool assets.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

TARGETS_GLOBS = [
    "src/android/**/*.kt",
    "src/android/**/*.xml",
    "src/android/**/*.py",
    "src/android/**/*.sh",
    "src/android/**/*.json",
    "src/android/**/*.properties",
    # rootfs tool source (Python CLI lives here)
    "src/android/app/src/main/assets/default_mount/**/*.py",
    "src/android/app/src/main/assets/default_mount/**/*.sh",
    "src/android/app/src/main/assets/default_mount/**/*",
    "docs/**/*.md",
    "scripts/*.sh",
    "scripts/*.py",
]

EXCLUDE_DIRS = {"Minis.xcodeproj"}

# Replacements ordered longest-first so prefixes don't shadow longer names.
REPLACEMENTS = [
    ("jarvis-mcp-cli", "jarvis-mcp-cli"),
    ("jarvis-mcp-daemon", "jarvis-mcp-daemon"),
    ("jarvis-sessions-cli", "jarvis-sessions-cli"),
    ("jarvis-browser-use", "jarvis-browser-use"),
    ("jarvis-model-use", "jarvis-model-use"),
    ("jarvis-scheduled", "jarvis-scheduled"),
    ("jarvis-config", "jarvis-config"),
    ("jarvis-debug", "jarvis-debug"),
    ("jarvis-open", "jarvis-open"),
    ("jarvis.db", "jarvis.db"),
]

# Directories/files to rename on disk (done after string rewrite).
RENAME_PATHS = [
    # (old_name, new_name) applied to any path segment under default_mount
    ("jarvis-mcp-cli", "jarvis-mcp-cli"),
    ("jarvis-open", "jarvis-open"),
]


def rewrite(text: str) -> str:
    """Apply all replacements. Order matters: longest-first ensures
    `jarvis-mcp-cli` is replaced before `minis-mcp` could match its prefix."""
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
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


def rename_dirs_on_disk() -> int:
    """Move directories/files whose name is an old CLI name -> new name."""
    moved = 0
    base = ROOT / "src" / "android" / "app" / "src" / "main" / "assets" / "default_mount"
    for old, new in RENAME_PATHS:
        for p in list(base.rglob(old)):
            target = p.parent / new
            if p == target or target.exists():
                continue
            if p.name != old:
                continue
            try:
                p.rename(target)
                moved += 1
                print(f"  RENAMED: {p.relative_to(ROOT)} -> {new}")
            except OSError as e:
                print(f"  ERROR renaming {p}: {e}", file=sys.stderr)
    return moved


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apply", action="store_true", help="write string changes (default: dry run)")
    ap.add_argument("--apply-rename", action="store_true",
                    help="also rename jarvis-mcp-cli / jarvis-open dirs on disk")
    args = ap.parse_args()

    changed = 0
    errored = 0
    total_hits = 0
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
        hits = sum(original.count(old) for old, _ in REPLACEMENTS)
        if new == original:
            continue
        changed += 1
        total_hits += hits
        if args.apply:
            try:
                p.write_text(new, encoding="utf-8")
            except OSError as e:
                errored += 1
                print(f"ERROR writing {p}: {e}", file=sys.stderr)
                continue
        try:
            print(f"{'WROTE' if args.apply else 'DRY  '} {p.relative_to(ROOT)}  ({hits} hits)")
        except Exception:
            pass
    print(f"\n{'Wrote' if args.apply else 'Would change'} {changed} file(s), "
          f"{total_hits} replacement(s); {errored} error(s).")

    if args.apply_rename:
        print("\n--- Renaming directories on disk ---")
        moved = rename_dirs_on_disk()
        print(f"Moved {moved} dir(s)/file(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
