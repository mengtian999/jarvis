#!/usr/bin/env python3
"""rename_url_scheme.py - Stage 2.1: minis:// URL scheme -> jarvis://.

Replaces the URL scheme everywhere it is referenced as a string literal or
in plist/manifest declarations. Three precise substitutions, each masked so
it cannot bleed into Layer 3 CLI names (jarvis-mcp-cli, jarvis-mcp-daemon) or
Layer 4 Bundle-ID schemes (com.openminis.app):

  1. `minis://`           -> `jarvis://`   (the URL literal, unambiguous)
  2. `"minis"`            -> `"jarvis"`    (bare scheme string literal — only
                              when it is the WHOLE string, via word-boundary
                              regex, after masking protected tokens)
  3. `minis-mcp`          -> `jarvis-mcp`  (OAuth callback scheme — masked so
                              `jarvis-mcp-cli` / `jarvis-mcp-daemon` survive)

NOT changed here (later stages): `jarvis-mcp-cli`, `jarvis-mcp-daemon`,
`com.openminis.app` (the OAuth scheme that mirrors the Bundle ID),
code symbols like MinisOpenUrlBroker / resolveMinisURL / sharedMinisSchemeHandler.

Run with --apply to write; default is a dry run.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

TARGETS_GLOBS = [
    "src/ios/**/*.swift",
    "src/ios/**/*.m",
    "src/ios/**/*.plist",
    "src/ios/**/*.pbxproj",
    "src/android/**/*.kt",
    "src/android/**/*.xml",
    "docs/**/*.md",
    "README.md",
    "BUILDING.md",
]

# Whole directories to skip (Layer 3 tool / Layer 4 project / rootfs assets).
EXCLUDE_DIRS = {"jarvis-mcp-cli", "Minis.xcodeproj", "default_mount"}

# Tokens that must NOT be altered by the bare-scheme regex. Masked first so
# the word-boundary brand regex can't reach into them. Ordered longest-first
# by the mask() helper.
PROTECTED = [
    "jarvis-mcp-cli", "jarvis-mcp-daemon",   # Layer 3 CLI / daemon (Stage 2.3)
    "com.openminis.app", "com.openminis",  # Layer 4 Bundle ID (Stage 3)
    "group.com.openminis", "openminis.app",
    # code symbols referencing minis (Layer 5, deferred) — keep their names
    "MinisOpenUrlBroker", "resolveMinisURL", "resolveMinisFileURL",
    "resolveMinisFileURLCached", "resolveMinisFileURLForNativeText",
    "sharedMinisSchemeHandler", "MinisURLSchemeHandler",
    "interceptMinisURL", "MinisImageProvider", "MinisImageFetcher",
    "isMinisURL", "minisAppGroupRoot", "ChatLinkResolver",
]


_SENTINEL = "\x00P{}\x00"


def mask(text: str) -> tuple[str, dict[str, str]]:
    table: dict[str, str] = {}
    masked = text
    for i, token in enumerate(sorted(PROTECTED, key=len, reverse=True)):
        if token in masked:
            sent = _SENTINEL.format(i)
            table[sent] = token
            masked = masked.replace(token, sent)
    return masked, table


def unmask(text: str, table: dict[str, str]) -> str:
    for sent, token in table.items():
        text = text.replace(sent, token)
    return text


def rewrite(text: str) -> str:
    masked, table = mask(text)
    # 1. URL literal — unambiguous, replace globally on the masked text.
    masked = masked.replace("minis://", "jarvis://")
    # 3. OAuth callback scheme (masked so -cli / -daemon variants survive).
    masked = masked.replace("minis-mcp", "jarvis-mcp")
    # 2. Bare scheme string literal: "minis" (double-quoted, whole word).
    #    Matches "minis" but NOT "minis-mcp" (already replaced above) nor
    #    any masked code symbol. Word boundaries via the quotes themselves.
    masked = re.sub(r'"minis"', '"jarvis"', masked)
    return unmask(masked, table)


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
            ol = original.splitlines()
            nl = new.splitlines()
            print(f"\n--- {p.relative_to(ROOT)}")
            shown = 0
            for i in range(max(len(ol), len(nl))):
                o = ol[i] if i < len(ol) else "<EOF>"
                n = nl[i] if i < len(nl) else "<EOF>"
                if o != n:
                    if shown < 15:
                        print(f"  L{i+1}:")
                        print(f"    - {o.strip()[:140]}")
                        print(f"    + {n.strip()[:140]}")
                        shown += 1
            if args.apply:
                print(f"  -> written")
            else:
                print(f"  (dry run)")
        except Exception:
            pass
    print(f"\n{'Wrote' if args.apply else 'Would change'} {changed} file(s); {errored} error(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
