#!/bin/bash
# MESHSAT-496 P0: Set GitHub repo metadata for cubeos-app/meshsat-android
# Run manually with a GitHub token that has repo admin access:
#   GITHUB_TOKEN=ghp_xxx bash docs/github-metadata.sh

set -euo pipefail

REPO="cubeos-app/meshsat-android"
TOKEN="${GITHUB_TOKEN:?Set GITHUB_TOKEN env var}"

echo "==> Setting description + homepage..."
curl -s -X PATCH "https://api.github.com/repos/${REPO}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  -d '{
    "description": "Standalone Android gateway + Reticulum Transport Node. Meshtastic BLE, Iridium SBD/IMT, Astrocast, APRS, SMS. No companion app, no cloud.",
    "homepage": "https://meshsat.net"
  }' | jq '{description, homepage}'

echo "==> Setting topics..."
curl -s -X PUT "https://api.github.com/repos/${REPO}/topics" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  -d '{
    "names": [
      "android", "kotlin", "jetpack-compose", "meshtastic", "iridium",
      "satellite", "reticulum", "mesh-networking", "lora", "aprs",
      "astrocast", "ble", "tactical", "off-grid"
    ]
  }' | jq '.names'

echo "==> Done. Verify at https://github.com/${REPO}"
