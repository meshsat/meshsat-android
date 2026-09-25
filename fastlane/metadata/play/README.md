# Google Play listing (the `play` flavor)

The listing for the Google Play edition, which has no SMS. `fastlane/metadata/android/` is the
F-Droid listing of the full app and is what F-Droid reads; never point Play at it, its texts
promise SMS three times.

- `en-US/title.txt`, `short_description.txt`, `full_description.txt`: same wording as the
  F-Droid listing minus SMS, plus "This edition does not send or receive SMS."
- `en-US/images/featureGraphic.png`: 1024 x 500, cut from the developer-page header
  (`Branding/Google Play/developer-header-4096x2304-logo.jpg`, the sticker logo lockup).
- `en-US/images/phoneScreenshots/1-4.png`: F-Droid screenshots 3 to 6 (passes, node, satellite,
  safety), scaled to 2400 high and padded to 1350 x 2400 by extending the edge pixels: the
  store listing form takes 9:16 only, and the phone is 1080 x 2424. 24-bit PNG, no alpha.
  Home and Setup are left out because they show the SMS lane and row of the full
  edition; retake them from a `play` build when the listing gets its own set.
- Release notes come from `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, which
  `scripts/bump-version.sh` writes; the Play upload job passes that directory.

Play limits: title 30, short description 80, full description 4000 characters.
