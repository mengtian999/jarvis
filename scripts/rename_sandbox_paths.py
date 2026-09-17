#!/usr/bin/env python3
"""rename_sandbox_paths.py - Stage 2.2: /var/jarvis/ -> /var/jarvis/.

Replaces the sandbox mount root path everywhere in source, tests, and docs.
`/var/jarvis` is an unambiguous path prefix (never a code symbol or protocol
identifier), so a plain global replace is safe — BUT the rootfs image must be
regenerated afterwards (scripts/prepare_*.sh, deps/prepare_alpine_rootfs.sh)
or the agent shell cannot find its mount points. That step needs a Linux/macOS
build host and is flagged in RENAME_MAP.md, not done here.

Also rewrites the rootfs build scripts themselves (prepare_*.sh, profile.d)
and the iOS default_mount profile, which are tracked source (not excluded).

Run with --apply to write; default is a dry run.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

TARGETS_GLOBS = [
    "src/ios/**/*.swift",
    "src/ios/**/*.m",
    "src/ios/**/*.mm",
    "src/ios/**/*.h",
    "src/android/**/*.kt",
    "src/android/**/*.xml",
    "src/android/**/*.java",
    "docs/**/*.md",
    "scripts/*.sh",
    "scripts/*.py",
    "README.md",
    "BUILDING.md",
    # rootfs profile scripts that bake the path into PS1 / env
    "src/android/app/src/main/assets/default_mount/**/*.sh",
    "src/android/app/src/main/assets/default_mount/etc/profile.d/*",
    "src/ios/default_mount/**/*.sh",
    "src/ios/default_mount/etc/profile.d/*",
    "deps/prepare_alpine_rootfs.sh",
    "deps/build_ish.sh",
    "deps/build_proot.sh",
]

# Directories to skip entirely (third-party tool source, Xcode project bundle).
EXCLUDE_DIRS = {"jarvis-mcp-cli", "Minis.xcodeproj"}


def rewrite(text: str) -> str:
    # /var/jarvis is an unambiguous path prefix. Plain replace is safe: it
    # cannot match a code symbol (those are CamelCase Minis*, lowercase minis
    # only appears in paths/schemes, and minis:// was already handled in 2.1).
    return text.replace("/var/jarvis", "/var/jarvis")


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
        hits = original.count("/var/jarvis")
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
          f"{total_hits} path replacement(s); {errored} error(s).")
    if args.apply:
        print("\n*** REMINDER: regenerate the Alpine rootfs now — run "
              "deps/prepare_alpine_rootfs.sh + scripts/prepare_android_sandbox.sh, "
              "or the agent shell will not find /var/jarvis mount points. ***")
    return 0


if __name__ == "__main__":
    sys.exit(main())
