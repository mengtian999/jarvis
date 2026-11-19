#!/usr/bin/env bash
# Pack local native iOS deps and publish as GitHub Release asset (one-time).
# Requires: gh auth login
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="ci-prebuilt-deps"
ASSET="ios-prebuilt-deps.tar.gz"
OUT="${TMPDIR:-/tmp}/$ASSET"

cd "$ROOT"
for p in deps/frameworks deps/libs deps/include deps/resources; do
  [[ -e "$p" ]] || { echo "missing $p — build native deps first"; exit 1; }
done

echo "Packing → $OUT"
tar -czf "$OUT" -C deps frameworks libs include resources
ls -lh "$OUT"

command -v gh >/dev/null || { echo "install gh: brew install gh"; exit 1; }
gh auth status

if gh release view "$TAG" >/dev/null 2>&1; then
  gh release upload "$TAG" "$OUT" --clobber
else
  gh release create "$TAG" "$OUT" \
    --title "iOS prebuilt deps for CI" \
    --notes "Native frameworks/libs/rootfs used by GitHub Actions ios-appstore workflow. Not an app release."
fi
echo "Done: release $TAG / $ASSET"
