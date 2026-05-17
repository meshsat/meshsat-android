# Steering — Repo conventions

## Build

```bash
# Build runs on nllei01androidsdk01 only — ADR-0002
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew :app:assembleDebug'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew :app:testDebugUnitTest'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew :app:lintDebug'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew :app:assembleRelease'   # signed APK
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

`type` ∈ `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `perf`. `scope` is the affected package (e.g. `crypto`, `engine`, `ui/screens`). `[MESHSAT-XX]` references YouTrack issue per CubeOS convention.

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
      java/com/cubeos/meshsat/
        MainActivity.kt
        MeshSatApp.kt
        <29 top-level packages>/
    src/test/
      java/com/cubeos/meshsat/
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
- Build signed APK via `assembleRelease` (signing keys in CI variables vault).
- Upload APK to GitLab Releases under the tag.
- Optionally push to Play Store via the standard upload flow (separate concern — out of CI for now).
