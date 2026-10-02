#!/usr/bin/env python3
"""Replace the phone screenshots of the Google Play listing (MESHSAT-1346).

Run by hand, never by CI: a listing change is a submission to Google's review. Talks to the
Google Play Developer Publishing API over REST with the same service-account key as
publish-play.py:

  1. edits.insert;
  2. edits.images.deleteall for phoneScreenshots, then edits.images.upload for 1.png, 2.png, ...
     from fastlane/metadata/play/<language>/images/phoneScreenshots, in that order;
  3. edits.commit.

The files come from scripts/frame-play-screenshots.py. `--dry-run` opens an edit, prints what
the listing holds now, and abandons the edit: nothing changes.

Usage:
  publish-play-listing.py --service-account /path/key.json [--dry-run]
  ... | publish-play-listing.py --service-account -        (the key on stdin)
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
IMAGE_TYPE = "phoneScreenshots"
MAX_SCREENSHOTS = 8  # Play's limit per device type


def die(msg, resp=None):
    if resp is not None:
        msg = f"{msg}: HTTP {resp.status_code} {resp.text[:800]}"
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def token(path):
    if path == "-":
        creds = service_account.Credentials.from_service_account_info(json.load(sys.stdin), scopes=[SCOPE])
    else:
        creds = service_account.Credentials.from_service_account_file(path, scopes=[SCOPE])
    creds.refresh(Request())
    return creds.token


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--service-account", required=True, help="key file, or - for stdin")
    ap.add_argument("--package", default="net.meshsat.android")
    ap.add_argument("--language", default="en-US")
    ap.add_argument("--metadata", default="fastlane/metadata/play")
    ap.add_argument("--dry-run", action="store_true", help="show the listing's images, change nothing")
    a = ap.parse_args()

    folder = pathlib.Path(a.metadata) / a.language / "images" / IMAGE_TYPE
    files = sorted(folder.glob("*.png"), key=lambda f: int(f.stem))
    if not files:
        die(f"no screenshots under {folder}")
    if len(files) > MAX_SCREENSHOTS:
        die(f"{len(files)} screenshots, Play takes at most {MAX_SCREENSHOTS}")

    s = requests.Session()
    s.headers["Authorization"] = f"Bearer {token(a.service_account)}"
    base = f"{API}/{a.package}"

    r = s.post(f"{base}/edits", json={})
    if r.status_code != 200:
        die("edits.insert failed (is the service account invited on the app?)", r)
    edit = r.json()["id"]
    images = f"{base}/edits/{edit}/listings/{a.language}/{IMAGE_TYPE}"

    r = s.get(images)
    if r.status_code != 200:
        s.delete(f"{base}/edits/{edit}")
        die("edits.images.list failed", r)
    current = r.json().get("images", [])
    print(f"the listing holds {len(current)} {IMAGE_TYPE} for {a.language}:")
    for img in current:
        print(f"  {img.get('id')}  sha256 {img.get('sha256', '')[:16]}")

    if a.dry_run:
        r = s.delete(f"{base}/edits/{edit}")
        print(f"dry run: edit abandoned (HTTP {r.status_code}); would upload {[f.name for f in files]}")
        return

    r = s.delete(images)
    if r.status_code != 200:
        s.delete(f"{base}/edits/{edit}")
        die("edits.images.deleteall failed (the key needs the store presence permission)", r)
    for f in files:
        with f.open("rb") as fh:
            r = s.post(
                f"{UPLOAD}/{a.package}/edits/{edit}/listings/{a.language}/{IMAGE_TYPE}?uploadType=media",
                headers={"Content-Type": "image/png"},
                data=fh,
                timeout=300,
            )
        if r.status_code != 200:
            s.delete(f"{base}/edits/{edit}")
            die(f"edits.images.upload({f.name}) failed", r)
        print(f"  uploaded {f.name}  sha256 {r.json()['image'].get('sha256', '')[:16]}")

    r = s.post(f"{base}/edits/{edit}:commit")
    if r.status_code != 200:
        die("edits.commit failed", r)
    print(f"committed: {len(files)} {IMAGE_TYPE} for {a.language} (sent to Google's review)")


if __name__ == "__main__":
    main()
