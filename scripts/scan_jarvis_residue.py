#!/usr/bin/env python3
"""scan_jarvis_residue.py - report remaining `minis` identifiers in source.

Run after each rename stage to verify no `minis` residue survives in the
layers already migrated. Excludes build artifacts, third-party submodules,
and logs.

Usage:
    python scripts/scan_jarvis_residue.py            # scan all layers
    python scripts/scan_jarvis_residue.py --stage 1   # only brand strings
    python scripts/scan_jarvis_residue.py --root /path/to/repo

Exit code 0 = clean for the selected layers; 1 = residue found.

See RENAME_MAP.md for the full mapping. This scanner is a helper, not a
gate: a hit in a layer you have not yet migrated is expected and OK.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# --- Layers ----------------------------------------------------------------
# Each entry: (layer number, description, [regex patterns]).
# Patterns are case-sensitive unless noted; search the raw file text.
LAYERS: list[tuple[int, str, list[str]]] = [
    (1, "brand strings  (Minis word in UI/docs)", [
        r'\bMinis\b',
    ]),
    (2, "URL schemes & deep links", [
        r'minis://',
        r'\bminis-mcp\b',
        r'forURLScheme:\s*"minis"',
        r'scheme\s*==\s*"minis"',
        r'scheme\s*!=\s*"minis"',
    ]),
    (3, "sandbox paths & offload CLI", [
        r'/var/jarvis',
        r'MinisChat',
        r'MinisFileProvider',
        r'\bMinisConfig\b',
        r'\bjarvis-config\b',
        r'\bjarvis-mcp-cli\b',
        r'\bjarvis-browser-use\b',
        r'\bjarvis-model-use\b',
        r'\bjarvis-sessions-cli\b',
        r'\bjarvis-debug\b',
        r'\bjarvis-scheduled\b',
        r'jarvis-mcp-daemon',
        r'root@minis',
    ]),
    (4, "system identifiers (Bundle ID / App Group / project)", [
        r'com\.openminis',
        r'group\.com\.openminis',
        r'Minis\.xcodeproj',
        r'Minis\.entitlements',
        r'rootProject\.name\s*=\s*"Minis"',
        r'applicationId\s*=\s*"com\.openminis',
        r'namespace\s*=\s*"com\.openminis',
        r'com/openminis/app',
    ]),
    # [T-jarvisbak-rename] The .jarvisbak rename replaced minisbak everywhere.
    # This layer keeps it from creeping back: any new minisbak / MBK1 (the old
    # crypto magic) is a regression of the package-format rename.
    (5, "backup package format (minisbak → jarvisbak)", [
        r'minisbak',
        r'MBK1',
    ]),
]

# --- Excluded paths --------------------------------------------------------
# Build output, third-party submodules, vendored sources, logs.
EXCLUDE_DIR_PARTS = {
    "build", ".gradle", ".cxx", ".tmp_deb", ".idea", ".swiftpm",
    "DerivedData", "Pods", ".build", "xcuserdata",
    # third-party native deps (submodules / vendored)
    "ish", "proot", "talloc", "ffmpeg", "lame", "alpine",
    # dev-only log dump
    ".build_log", ".zcode",
}
EXCLUDE_SUFFIXES = {".pyc", ".png", ".jpg", ".svg", ".bin", ".so", ".a", ".db"}

SCAN_SUFFIXES = {
    ".swift", ".kt", ".java", ".xml", ".m", ".mm", ".h", ".c", ".cpp",
    ".py", ".sh", ".md", ".txt", ".json", ".js", ".ts",
    ".gradle", ".kts", ".properties", ".plist", ".pbxproj", ".entitlements",
    ".xcconfig", ".yaml", ".yml", ".toml",
}


def iter_source_files(root: Path):
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix not in SCAN_SUFFIXES:
            continue
        if any(part in EXCLUDE_DIR_PARTS for part in path.parts):
            continue
        yield path


def scan(root: Path, stages: set[int]) -> dict[int, list[tuple[Path, int, str]]]:
    hits: dict[int, list[tuple[Path, int, str]]] = {n: [] for n, _, _ in LAYERS}
    compiled = {
        n: [re.compile(p) for p in pats]
        for n, _, pats in LAYERS
        if n in stages
    }
    for f in iter_source_files(root):
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for n, patterns in compiled.items():
            for pat in patterns:
                for m in pat.finditer(text):
                    line_no = text.count("\n", 0, m.start()) + 1
                    snippet = text.splitlines()[line_no - 1].strip()[:160]
                    hits[n].append((f, line_no, snippet))
    return hits


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".", help="repository root")
    ap.add_argument(
        "--stage",
        type=int,
        action="append",
        help="layer(s) to scan (1-4). Repeatable. Default: all.",
    )
    args = ap.parse_args()

    stages = set(args.stage) if args.stage else {n for n, _, _ in LAYERS}
    unknown = stages - {n for n, _, _ in LAYERS}
    if unknown:
        ap.error(f"unknown stage(s): {sorted(unknown)} (valid: 1-4)")

    # Force UTF-8 stdout so the ☐ checkbox / CJK snippets don't crash on a
    # Windows GBK console (the same class of bug that aborted the apply run).
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    root = Path(args.root).resolve()
    if not root.exists():
        ap.error(f"root not found: {root}")

    hits = scan(root, stages)

    total = 0
    for n, desc, _ in LAYERS:
        if n not in stages:
            continue
        rows = hits[n]
        total += len(rows)
        label = f"Layer {n}: {desc}"
        print(f"\n=== {label}  ({len(rows)} hits) ===")
        # Group by file for readability; cap per layer to avoid flooding.
        by_file: dict[Path, list[tuple[int, str]]] = {}
        for f, ln, snip in rows:
            by_file.setdefault(f, []).append((ln, snip))
        for f in sorted(by_file):
            try:
                rel = f.relative_to(root)
            except ValueError:
                rel = f
            for ln, snip in sorted(by_file[f]):
                print(f"  {rel}:{ln}: {snip}")
            if sum(len(v) for v in by_file.values()) > 200:
                print("  ... (truncated, refine --stage to narrow)")
                break

    print(f"\nTotal residue in scanned layers: {total}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
