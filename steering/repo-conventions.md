# Steering — Repo conventions

## Build

```bash
# Build runs on nllei01androidsdk01 only — ADR-0002
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/meshsat/meshsat-android && ./gradlew :app:assembleFdroidDebug'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/meshsat/meshsat-android && ./gradlew :app:testFdroidDebugUnitTest'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/meshsat/meshsat-android && ./gradlew :app:lintFdroidDebug'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/meshsat/meshsat-android && ./gradlew :app:assembleFdroidRelease'   # signed APKs (full edition); bundlePlayRelease for the Google Play AAB
```

## Version conventions

- `versionCode` is a monotonically-increasing integer (currently 51).
- `versionName` is semver-ish (currently 2.8.6).
- Bump both in the same commit; the bump commit message starts with `chore: bump version to v<X.Y.Z>` per CubeOS Article XVI.

## Branches

- `main` is the always-deployable branch.
- Feature branches: `feat/<short-slug>` for human work; `merge/<feature_id>` for parallel-dev waves per ADR-0003.
- MRs: opened against `main`. Reviewers + CI pipeline gate the merge.

## Commit messages

```
type(scope): description [MESHSAT-XX]
```

`type` ∈ `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `perf`. `scope` is the affected package (e.g. `crypto`, `engine`, `ui/screens`). `[MESHSAT-XX]` references the YouTrack issue in project MESHSAT.

Operator identity:

```
git -c user.name="Kyriakos Papadopoulos" -c user.email="ncpjfuzl@mxmx.email" commit ...
```

## File layout

```
/
  app/
    build.gradle.kts            ← versionCode, dependencies
    src/main/
      AndroidManifest.xml
      java/net/meshsat/android/
        MainActivity.kt
        MeshSatApp.kt
        <29 top-level packages>/
    src/test/
      java/net/meshsat/android/
        <FLAT — 45 *Test.kt files>
  build.gradle.kts              ← top-level Gradle config
  settings.gradle.kts
  PROJECT.json + PROJECT.md     ← spec-kit charter
  constitution.md               ← hard rules
  steering/                     ← this dir
  adr/                          ← architectural decisions
  spec/                         ← feature specs
  .agentic/slot-config.entry.json ← parallel-dev slot
  .gitignore                    ← CLAUDE.md + .claude/ excluded
```

## Release

- Tag `v<X.Y.Z>` on the bump commit.
- Build the signed fdroid APKs via `assembleFdroidRelease` and the Google Play AAB via `bundlePlayRelease` (signing keys in OpenBao). Two flavors: `fdroid` (full) and `play` (no SMS, MESHSAT-1335).
- Upload APK to GitLab Releases under the tag.
- The play AAB goes to the Play Console through the `publish-play` job (`scripts/publish-play.py`, Publisher API, track `PLAY_TRACK`, MESHSAT-1341). Google counts review time from the last submitted change, so the job is manual (`when: manual` and `allow_failure: true` on its rule) while a change is in Google's review, and a tag then uploads nothing by itself: from 28 Sep to 2 Oct 2026 for the first production release (MESHSAT-1395), and since 2 Oct 2026 for the listing's new screenshots (MESHSAT-1346). When no review is pending the two lines come off and every tag uploads by itself.
