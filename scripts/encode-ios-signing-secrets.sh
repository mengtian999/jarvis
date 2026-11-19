#!/usr/bin/env bash
# Print base64 blobs + ASC key text to paste into GitHub Actions Secrets.
# Usage:
#   ./scripts/encode-ios-signing-secrets.sh \
#     /path/to/dist.p12 \
#     /path/to/Jarvis\ AppStore.mobileprovision \
#     /path/to/Jarvis\ ShareExtension\ AppStore.mobileprovision \
#     /path/to/Jarvis\ FileProvider\ AppStore.mobileprovision \
#     /path/to/Jarvis\ AgentWidget\ AppStore.mobileprovision \
#     /path/to/AuthKey_CPTT4KYUQY.p8
set -euo pipefail

if [[ $# -lt 6 ]]; then
  echo "usage: $0 dist.p12 app.mobileprovision share.mobileprovision fileprovider.mobileprovision widget.mobileprovision AuthKey.p8"
  exit 1
fi

P12="$1"
APP="$2"
SHARE="$3"
FP="$4"
WIDGET="$5"
P8="$6"

echo "===== BUILD_CERTIFICATE_BASE64 ====="
base64 -i "$P12"
echo
echo "===== PROVISION_APP ====="
base64 -i "$APP"
echo
echo "===== PROVISION_SHARE ====="
base64 -i "$SHARE"
echo
echo "===== PROVISION_FILEPROVIDER ====="
base64 -i "$FP"
echo
echo "===== PROVISION_WIDGET ====="
base64 -i "$WIDGET"
echo
echo "===== APP_STORE_CONNECT_API_KEY (paste file body) ====="
cat "$P8"
echo
echo "===== also set ====="
echo "P12_PASSWORD=<your p12 password>"
echo "KEYCHAIN_PASSWORD=<any random string>"
echo "APP_STORE_CONNECT_KEY_ID=CPTT4KYUQY"
echo "APP_STORE_CONNECT_ISSUER_ID=836c0ea0-1e2e-4473-9128-ce229766e00f"
