#!/usr/bin/env bash
# Bump the app version and write the changelog files that go with it.
#
# A release carries five APKs (one per processor plus universal), each with its own
# versionCode: base * 10 + 0..4. F-Droid reads its "What's new" from
# fastlane/metadata/android/en-US/changelogs/<versionCode>.txt, so every release needs
# five files, not one. Writing them by hand is how they end up missing, and a missing
# file shows as no changelog at all in the F-Droid client (MESHSAT-1260).
#
# Usage:
#   scripts/bump-version.sh 2.14.8 "One or two sentences about what changed."
#   scripts/bump-version.sh 2.14.8 < notes.txt
#
# Leaves everything staged-but-uncommitted so the diff can be read before committing.
set -euo pipefail

cd "$(dirname "$0")/.."

GRADLE_FILE="app/build.gradle.kts"
CHANGELOG_DIR="fastlane/metadata/android/en-US/changelogs"

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
    echo "usage: $0 <versionName> [changelog text]" >&2
    exit 2
fi
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "error: '$VERSION' is not a three-part version like 2.14.8" >&2
    exit 2
fi

if [[ $# -ge 2 ]]; then
    TEXT="$2"
else
    TEXT="$(cat)"
fi
TEXT="$(printf '%s' "$TEXT" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
if [[ -z "$TEXT" ]]; then
    echo "error: no changelog text given" >&2
    exit 2
fi
# F-Droid truncates long entries in the client.
if [[ ${#TEXT} -gt 500 ]]; then
    echo "warning: changelog is ${#TEXT} characters; F-Droid shows about 500." >&2
fi

CURRENT_CODE="$(grep -oP '^\s*versionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE" | head -1)"
CURRENT_NAME="$(grep -oP '^\s*versionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" | head -1)"
if [[ -z "$CURRENT_CODE" || -z "$CURRENT_NAME" ]]; then
    echo "error: could not read the current version from $GRADLE_FILE." >&2
    echo "       Both must stay literal numbers there: F-Droid's checkupdates reads" >&2
    echo "       them with a regex that only matches a number (MESHSAT-1163)." >&2
    exit 1
fi

NEW_CODE=$((CURRENT_CODE + 1))
echo "  $CURRENT_NAME ($CURRENT_CODE)  ->  $VERSION ($NEW_CODE)"

sed -i -E "s/^(\s*versionCode\s*=\s*)[0-9]+/\1${NEW_CODE}/" "$GRADLE_FILE"
sed -i -E "s/^(\s*versionName\s*=\s*)\"[^\"]+\"/\1\"${VERSION}\"/" "$GRADLE_FILE"

mkdir -p "$CHANGELOG_DIR"
written=()
for abi_digit in 0 1 2 3 4; do
    code="$((NEW_CODE * 10 + abi_digit))"
    printf '%s\n' "$TEXT" > "$CHANGELOG_DIR/${code}.txt"
    written+=("${code}.txt")
done
echo "  changelogs: ${written[*]}  (universal, armeabi-v7a, arm64-v8a, x86, x86_64)"

git add "$GRADLE_FILE" "$CHANGELOG_DIR"

cat <<EOF

Staged. Read the diff, then:

  git diff --cached
  git -c user.name="Kyriakos Papadopoulos" -c user.email="ncpjfuzl@mxmx.email" \\
      commit -m "chore: bump version to v${VERSION}"
  git -c user.name="Kyriakos Papadopoulos" -c user.email="ncpjfuzl@mxmx.email" \\
      tag -a "v${VERSION}" -m "MeshSat Android v${VERSION}"
  git push origin main && git push origin "v${VERSION}"
EOF
