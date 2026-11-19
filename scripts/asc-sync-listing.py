#!/usr/bin/env python3
"""Sync App Store listing copy (name/subtitle/description/keywords/URLs) via ASC API.

Usage:
  APP_STORE_CONNECT_KEY_ID=... \\
  APP_STORE_CONNECT_ISSUER_ID=... \\
  APP_STORE_CONNECT_API_KEY_PATH=/path/AuthKey_XXX.p8 \\
  python3 scripts/asc-sync-listing.py

Does NOT declare the app as a generative-AI content service — listing copy
positions Bit Jarvis as a local-first utility / agent toolkit (BYOK).
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    import jwt  # PyJWT
except ImportError:
    sys.stderr.write("Need PyJWT: pip install PyJWT cryptography\n")
    raise

ROOT = Path(__file__).resolve().parents[1]
META_DIR = ROOT / "asc" / "metadata"
APP_ID = os.environ.get("ASC_APP_ID", "6836590085")  # com.bitjarvis.app


def token() -> str:
    kid = os.environ["APP_STORE_CONNECT_KEY_ID"]
    iss = os.environ["APP_STORE_CONNECT_ISSUER_ID"]
    key_path = Path(os.environ.get(
        "APP_STORE_CONNECT_API_KEY_PATH",
        str(Path.home() / "Desktop" / f"AuthKey_{kid}.p8"),
    ))
    private_key = key_path.read_text()
    now = int(time.time())
    return jwt.encode(
        {"iss": iss, "iat": now, "exp": now + 20 * 60, "aud": "appstoreconnect-v1"},
        private_key,
        algorithm="ES256",
        headers={"alg": "ES256", "kid": kid},
    )


def api(method: str, path: str, body: dict | None = None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        f"https://api.appstoreconnect.apple.com{path}",
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token()}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        err = e.read().decode()
        raise SystemExit(f"ASC {method} {path} -> {e.code}: {err}") from e


def latest_version_id(app_id: str) -> str:
    # Prefer editable versions (PREPARE_FOR_SUBMISSION / DEVELOPER_REJECTED / REJECTED / METADATA_REJECTED)
    q = (
        f"/v1/apps/{app_id}/appStoreVersions"
        "?filter[platform]=IOS&limit=10"
        "&sort=-createdDate"
    )
    data = api("GET", q).get("data") or []
    if not data:
        raise SystemExit("No appStoreVersions found")
    preferred = {
        "PREPARE_FOR_SUBMISSION",
        "DEVELOPER_REJECTED",
        "REJECTED",
        "METADATA_REJECTED",
        "WAITING_FOR_REVIEW",
        "READY_FOR_REVIEW",
    }
    for v in data:
        st = (v.get("attributes") or {}).get("appStoreState")
        if st in preferred:
            print("using version", v["id"], st, (v.get("attributes") or {}).get("versionString"))
            return v["id"]
    v = data[0]
    print("fallback version", v["id"], (v.get("attributes") or {}).get("appStoreState"),
          (v.get("attributes") or {}).get("versionString"))
    return v["id"]


def localization_map(version_id: str) -> dict[str, str]:
    data = api(
        "GET",
        f"/v1/appStoreVersions/{version_id}/appStoreVersionLocalizations?limit=50",
    ).get("data") or []
    return {(x.get("attributes") or {}).get("locale"): x["id"] for x in data}


def upsert_locale(version_id: str, loc_id: str | None, meta: dict):
    attrs = {
        "description": meta["description"],
        "keywords": meta["keywords"][:100],
        "marketingUrl": meta["marketingUrl"],
        "promotionalText": meta.get("promotionalText", "")[:170],
        "supportUrl": meta["supportUrl"],
        "whatsNew": meta.get("whatsNew", "")[:4000],
    }
    # name/subtitle are on appInfo localization for some states; try version loc first
    if meta.get("subtitle"):
        attrs["subtitle"] = meta["subtitle"][:30]
    body = {"data": {"type": "appStoreVersionLocalizations", "attributes": attrs}}
    if loc_id:
        body["data"]["id"] = loc_id
        api("PATCH", f"/v1/appStoreVersionLocalizations/{loc_id}", body)
        print("patched", meta["locale"], loc_id)
    else:
        body["data"]["attributes"]["locale"] = meta["locale"]
        body["data"]["relationships"] = {
            "appStoreVersion": {"data": {"type": "appStoreVersions", "id": version_id}}
        }
        created = api("POST", "/v1/appStoreVersionLocalizations", body)
        print("created", meta["locale"], created.get("data", {}).get("id"))


def sync_app_info_urls(app_id: str, privacy_url: str):
    # App-level privacy policy URL lives on appInfos -> appInfoLocalizations
    infos = api("GET", f"/v1/apps/{app_id}/appInfos?limit=5").get("data") or []
    if not infos:
        print("no appInfos")
        return
    info_id = infos[0]["id"]
    locs = api("GET", f"/v1/appInfos/{info_id}/appInfoLocalizations?limit=50").get("data") or []
    for loc in locs:
        lid = loc["id"]
        locale = (loc.get("attributes") or {}).get("locale")
        name = "Bit Jarvis" if locale and locale.startswith("zh") else "Bit Jarvis"
        subtitle = "本地私密的端侧 Agent 工具" if locale and locale.startswith("zh") else "Private on-device agent toolkit"
        body = {
            "data": {
                "type": "appInfoLocalizations",
                "id": lid,
                "attributes": {
                    "name": name[:30],
                    "subtitle": subtitle[:30],
                    "privacyPolicyUrl": privacy_url,
                },
            }
        }
        try:
            api("PATCH", f"/v1/appInfoLocalizations/{lid}", body)
            print("appInfoLocalization", locale, "ok")
        except SystemExit as e:
            print("appInfoLocalization skip", locale, e)


def main():
    version_id = latest_version_id(APP_ID)
    existing = localization_map(version_id)
    for path in sorted(META_DIR.glob("*.json")):
        meta = json.loads(path.read_text())
        upsert_locale(version_id, existing.get(meta["locale"]), meta)
    privacy = "https://www.bitjarvis.chat/privacy.html"
    sync_app_info_urls(APP_ID, privacy)
    print("ASC listing sync done — no AIGC content-service declaration added.")


if __name__ == "__main__":
    main()
