#!/usr/bin/env python3
"""Upload the Google Play edition's AAB to a Play Console track (MESHSAT-1341).

Runs in the tag pipeline after build-release. Talks to the Google Play Developer Publishing
API directly over REST with a service-account key, no fastlane:

  1. an OAuth2 access token from the service-account JSON (google-auth);
  2. edits.insert, edits.bundles.upload (the AAB, as application/octet-stream);
  3. edits.tracks.update on the chosen track, status "completed", release notes from
     fastlane/metadata/android/en-US/changelogs/<versionCode>.txt (the file bump-version.sh
     writes; the Play AAB carries base*10, the universal number);
  4. edits.commit. A track that Google reviews (alpha, production) is sent for review by
     that commit; internal testing goes live at once.

Google's rule: the FIRST bundle of a new app must be uploaded in the Console by hand, which was
done on 25 Sep 2026 (1180). The service account needs "Release apps to testing tracks" on the
app in Users and permissions; that is what limits the blast radius of this key.

Usage:
  publish-play.py --aab meshsat-android-2.19.2-play.aab --track alpha \
      --service-account /path/key.json [--changelogs fastlane/metadata/android/en-US/changelogs]
      [--package net.meshsat.android] [--dry-run]
"""
import argparse
import json
import pathlib
import sys

import requests
from google.oauth2 import service_account
from google.auth.transport.requests import Request

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
NOTES_MAX = 500  # Play's limit per language


def die(msg, resp=None):
    if resp is not None:
        msg = f"{msg}: HTTP {resp.status_code} {resp.text[:800]}"
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def token(path):
    creds = service_account.Credentials.from_service_account_file(path, scopes=[SCOPE])
    creds.refresh(Request())
    return creds.token


def release_notes(changelogs, version_code):
    """The changelog for this versionCode, or the universal one (same base, last digit 0)."""
    for code in (version_code, version_code // 10 * 10):
        f = pathlib.Path(changelogs) / f"{code}.txt"
        if f.is_file() and f.read_text().strip():
            text = f.read_text().strip()
            return text if len(text) <= NOTES_MAX else text[: NOTES_MAX - 1] + "…"
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--aab", required=True)
    ap.add_argument("--track", default="alpha")
    ap.add_argument("--service-account", required=True)
    ap.add_argument("--package", default="net.meshsat.android")
    ap.add_argument("--changelogs", default="fastlane/metadata/android/en-US/changelogs")
    ap.add_argument("--version-name", default="")
    ap.add_argument("--dry-run", action="store_true", help="open and abandon an edit, upload nothing")
    a = ap.parse_args()

    aab = pathlib.Path(a.aab)
    if not aab.is_file():
        die(f"no such file: {aab}")

    s = requests.Session()
    s.headers["Authorization"] = f"Bearer {token(a.service_account)}"
    base = f"{API}/{a.package}"

    r = s.post(f"{base}/edits", json={})
    if r.status_code != 200:
        die("edits.insert failed (is the service account invited on the app?)", r)
    edit = r.json()["id"]
    print(f"edit {edit} opened for {a.package}")

    if a.dry_run:
        r = s.delete(f"{base}/edits/{edit}")
        print(f"dry run: edit abandoned (HTTP {r.status_code}); credentials and app access are good")
        return

    print(f"uploading {aab.name} ({aab.stat().st_size // 1_000_000} MB)...")
    with aab.open("rb") as fh:
        r = s.post(
            f"{UPLOAD}/{a.package}/edits/{edit}/bundles?uploadType=media",
            headers={"Content-Type": "application/octet-stream"},
            data=fh,
            timeout=600,
        )
    if r.status_code != 200:
        die("bundles.upload failed", r)
    code = int(r.json()["versionCode"])
    print(f"  accepted as versionCode {code}")

    notes = release_notes(a.changelogs, code)
    release = {
        "name": f"{code} ({a.version_name})" if a.version_name else str(code),
        "versionCodes": [str(code)],
        "status": "completed",
    }
    if notes:
        release["releaseNotes"] = [{"language": "en-US", "text": notes}]
    else:
        print(f"  no changelog for {code} under {a.changelogs}; release without notes")

    r = s.put(f"{base}/edits/{edit}/tracks/{a.track}", json={"track": a.track, "releases": [release]})
    if r.status_code != 200:
        die(f"tracks.update({a.track}) failed", r)

    r = s.post(f"{base}/edits/{edit}:commit")
    if r.status_code != 200:
        die("edits.commit failed", r)
    print(f"committed: {release['name']} on track {a.track}" + (" (sent for review where the track needs it)"))


if __name__ == "__main__":
    main()
