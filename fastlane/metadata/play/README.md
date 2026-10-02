# Google Play listing (the `play` flavor)

The listing for the Google Play edition, which has no SMS. `fastlane/metadata/android/` is the
F-Droid listing of the full app and is what F-Droid reads; never point Play at it, its texts
promise SMS three times.

- `en-US/title.txt`, `short_description.txt`, `full_description.txt`: same wording as the
  F-Droid listing minus SMS, plus "This edition does not send or receive SMS."
- `en-US/images/featureGraphic.png`: 1024 x 500, cut from the developer-page header
  (`Branding/Google Play/developer-header-4096x2304-logo.jpg`, the sticker logo lockup).
- `en-US/images/phoneScreenshots/1-5.png`: Home, People, satellite passes, map and the node
  screen of 2.19.4, captured on 2 Oct 2026 and framed by `scripts/frame-play-screenshots.py`:
  1440 x 2560 (9:16), 24-bit PNG, one headline above each capture, no device frame. The script's
  header quotes Google's rules for listing screenshots. The captures come from the full edition,
  so Home shows its SMS lane; the listing's texts still never promise SMS. A name on Home's SOS
  card is blurred. The raw captures are not in the repo.
- `scripts/publish-play-listing.py` replaces the listing's phone screenshots with the files in
  that folder. It is run by hand, never by CI: a listing change is a submission to Google's
  review. `--dry-run` shows what the listing holds and changes nothing.
- Release notes come from `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, which
  `scripts/bump-version.sh` writes; the Play upload job passes that directory.

Play limits: title 30, short description 80, full description 4000 characters.
