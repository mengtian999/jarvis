#!/usr/bin/env python3
"""rename_bundleid_android.py - Stage 3 (Android): com.openminis.app -> com.jarvis.app.

Replaces the Android application ID / namespace / package path everywhere:
  - `com.openminis.app`  (package declarations, imports, applicationId, namespace)
  - `com/openminis/app`  (directory path references in comments/build configs)
  - `com.openminis.MinisTests` / `MinisUITests` (test suite names, if present)

Then (--apply-rename) moves the Kotlin source tree:
  src/main/java/com/openminis/app/      -> src/main/java/com/jarvis/app/
  src/test/java/com/openminis/app/     -> src/test/java/com/jarvis/app/
  src/androidTest/java/com/openminis/app/ -> src/androidTest/java/com/openminis/app/

The directory move MUST run after the string rewrite so .kt files already
declare `package com.jarvis.app` when they land in the new path. Android
relative manifest names (`.MainActivity`) resolve against the namespace in
build.gradle.kts, so changing namespace there + the dir move is self-consistent.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

TARGETS_GLOBS = [
    "src/android/**/*.kt",
    "src/android/**/*.java",
    "src/android/**/*.xml",
    "src/android/**/*.kts",
    "src/android/**/*.properties",
    "src/android/**/*.py",
    "src/android/**/*.sh",
    "src/android/**/*.json",
    "src/android/**/*.md",
]

EXCLUDE_DIRS = {"build", ".gradle", ".cxx", ".tmp_deb", "Minis.xcodeproj"}

REPLACEMENTS = [
    ("com.openminis.app", "com.jarvis.app"),
    ("com/openminis/app", "com/jarvis/app"),
    # Test suite names (if any reference the old Bundle ID prefix)
    ("com.openminis.MinisTests", "com.jarvis.JarvisTests"),
    ("com.openminis.MinisUITests", "com.jarvis.JarvisUITests"),
]

# Source-set roots whose com/openminis/app subtree must move to com/jarvis/app.
SRC_ROOTS = [
    "src/android/app/src/main/java",
    "src/android/app/src/test/java",
    "src/android/app/src/androidTest/java",
]


def rewrite(text: str) -> str:
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


def move_source_tree() -> int:
    """Move com/openminis/app -> com/jarvis/app under each SRC_ROOT.
    Creates com/jarvis/ parents, moves the app dir, then removes the
    now-empty com/openminis/ tree."""
    moved = 0
    for rel in SRC_ROOTS:
        base = ROOT / rel
        old_app = base / "com" / "openminis" / "app"
        if not old_app.is_dir():
            continue
        new_app = base / "com" / "jarvis" / "app"
        new_app.mkdir(parents=True, exist_ok=True)
        # Move each child (preserve any pre-existing content in new_app).
        for child in list(old_app.iterdir()):
            target = new_app / child.name
            if target.exists():
                print(f"  SKIP (exists): {target.relative_to(ROOT)}", file=sys.stderr)
                continue
            child.rename(target)
            moved += 1
        # Clean up the empty com/openminis skeleton.
        old_minis = base / "com" / "openminis"
        try:
            old_minis.rmdir()  # only succeeds if empty
            print(f"  removed empty: {old_minis.relative_to(ROOT)}")
        except OSError:
            print(f"  NOT empty (left in place): {old_minis.relative_to(ROOT)}",
                  file=sys.stderr)
        print(f"  MOVED {old_app.relative_to(ROOT)} -> {new_app.relative_to(ROOT)} "
              f"({moved} entries so far)")
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
                    help="also move com/openminis/app -> com/jarvis/app on disk")
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
        print("\n--- Moving source tree com/openminis/app -> com/jarvis/app ---")
        moved = move_source_tree()
        print(f"Moved {moved} file/dir entries.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

