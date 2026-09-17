#!/usr/bin/env python3
"""rename_brand_strings.py - Layer 1 brand-string rewriter (Minis -> Jarvis).

Replaces ONLY user-visible brand occurrences of the word "Minis" with "Jarvis"
(or the Chinese localized form "贾维斯"). It deliberately leaves alone:

  * Android string resource `name=` keys (e.g. `settings_about_minis`) — these
    are code identifiers, renamed in Layer 4/5, not here.
  * Protocol/path identifiers: `minis://`, `/var/jarvis`, `jarvis-config`,
    `jarvis-mcp-cli`, `jarvis-model-use`, `jarvis-browser-use`, `jarvis-sessions-cli`,
    `jarvis-debug`, `jarvis-scheduled`, `jarvis-mcp-daemon`, `root@minis`,
    `MinisChat`, `MinisFileProvider`, `MinisConfig`.
  * Code symbols (CamelCase type/property names): `MinisApp`,
    `MinisURLSchemeHandler`, `MinisImageProvider`, etc. — Layer 5 (deferred).
  * URLs / repo paths: `openminis.app`, `OpenMinis/`, `com.openminis`,
    `group.com.openminis`.
  * `MinisSkills` / `AwesomeMinis` repo names (Layer 6 external).
  * iOS Info.plist `CFBundleURLSchemes` values (Layer 2).

What it DOES rewrite (the standalone brand word):
  * "Minis" as a standalone word in prose/values -> "Jarvis"
  * In files under res/values-zh* the brand is mapped to "贾维斯" to match the
    existing simplified-Chinese convention already started in values-zh.

Run with --apply to write; default is a dry run that prints the diff.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Files this reriter is allowed to touch (Layer 1 brand strings only).
# Broadened to the whole src tree so no user-visible brand word is missed;
# the PROTECTED list + per-file excludes (collect_files) keep protocol /
# code-symbol / Layer-4 identifiers untouched.
TARGETS_GLOBS = [
    # Android resources (all locales) + manifest + Kotlin sources
    "src/android/app/src/main/res/values*/strings.xml",
    "src/android/app/src/main/AndroidManifest.xml",
    "src/android/app/src/main/java/com/openminis/app/**/*.kt",
    "src/android/app/src/debug/**/*.kt",
    "src/android/app/src/debug/**/*.py",
    # iOS sources (Swift + Obj-C), EXCLUDING the jarvis-mcp-cli tool dir
    # (Layer 3, renamed as a whole later) and the Xcode project (Layer 4).
    "src/ios/**/*.swift",
    "src/ios/**/*.m",
    "src/ios/**/*.mm",
    "src/ios/**/*.h",
    # Top-level docs
    "README.md",
    "BUILDING.md",
    "CONTRIBUTING.md",
    ".github/**/*.md",
]

# Whole directories the reriter must never enter, even if a glob matches.
EXCLUDE_DIRS = {
    "jarvis-mcp-cli",   # Layer 3 offload tool (renamed atomically later)
    "Minis.xcodeproj", # Layer 4 (project rename)
    "default_mount",   # rootfs assets (Layer 3)
}


# --- Things we must NOT touch ----------------------------------------------
# Ordered longest-first so multi-word identifiers match before their stems.
PROTECTED = [
    # protocol / path identifiers (Layer 2/3)
    "minis://", "/var/jarvis", "jarvis-config", "jarvis-mcp-cli", "jarvis-mcp-daemon",
    "jarvis-model-use", "jarvis-browser-use", "jarvis-sessions-cli", "jarvis-debug",
    "jarvis-scheduled", "root@minis", "MinisChat", "MinisFileProvider",
    "MinisConfig",
    # code symbols (Layer 5, deferred)
    "MinisApp", "MinisURLSchemeHandler", "MinisImageProvider", "MinisOpenUrlBroker",
    "MinisMarkdownParser", "MinisShareSheet", "AskMinisIntent",
    "MinisAccessibilityService", "MinisNotificationListenerService",
    "resolveMinisURL", "minisAppGroupRoot", "minisConfigRoot",
    "sharedMinisSchemeHandler", "ensureMinisSymlinks", "MinisFsRouter",
    "MinisUserAgent", "MinisMediaViews", "MinisDebugLogReader",
    "MinisShortcutsProvider", "MinisConfigPermissionStore", "MinisURLPathDecoding",
    "MinisShare", "MinisFileProvider", "MinisTests", "MinisUITests",
    "MinisDebug", "MinisModelUse", "MinisSessions", "MinisBrowserUse",
    "MinisScheduled", "MinisConfigPermission",
    # Xcode project / entitlements filenames + build artifacts (Layer 4)
    "Minis.xcodeproj", "Minis.entitlements", "Minis.app.dSYM", "Minis*.xcarchive",
    "Minis.app.dSYM", "Minis.app", "DWARF/Minis",
    # runtime protocol identifiers (HTTP headers, env vars, resource styles)
    "X-Minis-Token", "MINIS_DEBUG_TOKEN", "Theme.Minis",
    # external assets (Layer 6) — includes the spaced brand "Open Minis" used in
    # third-party article titles / quotes that must not be falsified.
    "openminis.app", "Open Minis", "OpenMinis", "AwesomeMinis", "MinisSkills",
    "com.openminis", "group.com.openminis",
    "minis-accessibility_service", "minis-mcp",  # Layer 2 schemes
    "T-minis-textkit", "T-minis",  # task tags in comments
]

# A protected-token mask: replace each protected substring with a sentinel
# so the brand-word regex can't reach into it, then restore afterwards.
_SENTINEL = "\x00PROT{}\x00"


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


# Brand word: standalone "Minis" with word boundaries, capital M only.
BRAND_RE = re.compile(r"(?<![A-Za-z0-9_])Minis(?![A-Za-z0-9_])")


def rewrite(text: str, zh: bool) -> str:
    masked, table = mask(text)
    replacement = "贾维斯" if zh else "Jarvis"
    masked = BRAND_RE.sub(replacement, masked)
    return unmask(masked, table)


def is_chinese_file(p: Path) -> bool:
    return "values-zh" in p.as_posix()


def collect_files() -> list[Path]:
    out: list[Path] = []
    seen: set[Path] = set()
    for pat in TARGETS_GLOBS:
        for p in ROOT.glob(pat):
            if not p.is_file() or p in seen:
                continue
            # Skip any file whose path crosses an excluded directory
            # (jarvis-mcp-cli tool, Xcode project bundle, rootfs assets).
            if any(part in EXCLUDE_DIRS for part in p.parts):
                continue
            seen.add(p)
            out.append(p)
    return sorted(out)


def main() -> int:
    # Force UTF-8 stdout/stderr so special characters (CJK, ✧, em-dash, …) in
    # the diff output don't crash on Windows GBK consoles mid-run — a crash
    # here aborts the whole apply and leaves later files un-rewritten.
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
        zh = is_chinese_file(p)
        try:
            new = rewrite(original, zh)
        except Exception as e:  # masking/regex should never fail, but never abort the run
            errored += 1
            print(f"ERROR rewriting {p}: {e}", file=sys.stderr)
            continue
        if new == original:
            continue
        changed += 1
        # Apply the write FIRST, before printing the diff, so a print failure
        # (e.g. a console codec choking on a CJK / symbol char) cannot leave
        # the file half-rewritten or block the write of later files.
        if args.apply:
            try:
                p.write_text(new, encoding="utf-8")
            except OSError as e:
                errored += 1
                print(f"ERROR writing {p}: {e}", file=sys.stderr)
                continue
        # Diff printing is best-effort; never let it abort the loop.
        try:
            ol = original.splitlines()
            nl = new.splitlines()
            diffs = sum(1 for i in range(max(len(ol), len(nl)))
                        if (ol[i] if i < len(ol) else "<EOF>") != (nl[i] if i < len(nl) else "<EOF>"))
            print(f"\n--- {p.relative_to(ROOT)}  (zh={zh})")
            shown = 0
            for i in range(max(len(ol), len(nl))):
                o = ol[i] if i < len(ol) else "<EOF>"
                n = nl[i] if i < len(nl) else "<EOF>"
                if o != n:
                    if shown < 20:
                        print(f"  L{i+1}:")
                        print(f"    - {o.strip()[:140]}")
                        print(f"    + {n.strip()[:140]}")
                        shown += 1
            if args.apply:
                print(f"  -> written ({diffs} line(s) changed)")
            else:
                print(f"  (dry run; {diffs} line(s) would change)")
        except Exception:
            pass  # diff printing is cosmetic
    print(f"\n{'Wrote' if args.apply else 'Would change'} {changed} file(s); {errored} error(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
