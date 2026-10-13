# Build & release

> How to build, test, sign and ship Sportio. For the i18n checks specifically see [I18N.md](I18N.md).

---

## 1. Prerequisites

- **JDK 21** — the Gradle toolchain (`gradle/gradle-daemon-jvm.properties` → `toolchainVersion=21`).
  The foojay resolver can provision it. Source/target level is Java 17.
- **Android SDK** with the **`android-37.2`** platform (canary channel) and build-tools 36+.
- **Python 3** — only for the i18n checks (the Gradle build warns and skips if it is missing).
- **Gradle** is the wrapper: `./gradlew` (`gradlew.bat` on Windows). Gradle 9.7.1.

Put the SDK path in `local.properties` (never committed):

```properties
sdk.dir=C:/Path/To/Android/Sdk
```

---

## 2. The two repos and the composite build

- This repo (`SportioTV`) is `:app` + `:baselineprofile`.
- Core (`SportioTV_Core`) is `:core` + `:player-core` and is normally consumed as the **pinned Maven
  artifact** (`owntvCore` in `gradle/libs.versions.toml`).
- To build against a local core checkout, set in `~/.gradle/gradle.properties` (**never in the repo**):

  ```properties
  owntv.corePath=C:/path/to/SportioTV_Core
  ```

  The app's `settings.gradle.kts` picks it up and composite-builds core. CI leaves it unset.

`tv.own.owntv:libmpv` (the native engine) always comes from **upstream** OwnTV's Maven repo; nothing in
this fork rebuilds it.

---

## 3. Flavors and variants

`flavorDimensions += "abi"`:

| Flavor | ABI | Use |
|---|---|---|
| `standard` | `arm64-v8a`, `armeabi-v7a` | every real TV box — the everyday build |
| `x86_64` | `x86_64` | emulators and rare Intel boxes |

The native player only loads on a matching ABI — pick the right variant. Build types are `debug` and
`release` (release is R8-minified and resource-shrunk, `abortOnError` lint).

Common commands:

```bash
./gradlew :app:assembleStandardDebug          # arm, everyday
./gradlew :app:assembleX86_64Debug            # x86_64 (emulator)
./gradlew :app:assembleRelease                # both flavors, signed release
./gradlew :app:testStandardDebugUnitTest      # unit tests
./gradlew :app:lintStandardDebug              # lint
```

APKs land in `app/build/outputs/apk/<flavor>/<buildType>/`.

---

## 4. Versions

`applicationId = tv.sportio.app` (installs beside upstream OwnTV), `namespace = tv.own.owntv`,
`minSdk 26`, `targetSdk 36`, `compileSdk release(37) { minorApiLevel = 2 }`.

**Version comes from the environment:**

```bash
VERSION_NAME=1.0.1 VERSION_CODE=10001 ./gradlew :app:assembleRelease
```

The fallback is `99.99.99` / `99999`, pinned deliberately high so a local/dev APK always installs over
a published release. CI derives the version from the Git tag.

> **`compileSdk release(37){minorApiLevel=2}` is intentional.** API 37 exists only as the 37.2 canary
> platform, and 2026-era dependencies require `compileSdk >= 37` in their AAR metadata. Lowering it to
> 36 breaks the build.

---

## 5. Signing

Signing values come **from the environment**, never from the repo — in this order: env var, Gradle
property, then an optional out-of-repo properties file.

| Env var | Purpose |
|---|---|
| `KEYSTORE_FILE` | path to the `.jks` |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

```bash
VERSION_NAME=1.0.1 VERSION_CODE=10001 \
KEYSTORE_FILE=/path/sportio-release.jks KEYSTORE_PASSWORD=… KEY_ALIAS=sportio KEY_PASSWORD=… \
./gradlew :app:assembleRelease
```

Without a keystore the release task produces an unsigned/debug-signed APK — fine for local testing,
**not** for shipping: Android only accepts an update signed with the same key as the installed app.
**Never commit a keystore.**

The optional edge key for the shared metadata/subtitle proxy is `OWNTV_EDGE_KEY` (or
`OWNTV_EDGE_MASTER`, from which CI derives it) → `BuildConfig.TMDB_EDGE_KEY`. A blank key makes the app
use the unprotected base URL, which is exactly what a fork without secrets should do.

---

## 6. Gradle properties worth knowing

- `org.gradle.jvmargs=-Xmx4096m`, `kotlin.daemon.jvmargs=-Xmx8192m`, `org.gradle.workers.max=4` —
  bound peak memory of parallel Kotlin compilation. Raising worker count without raising memory causes
  confusing `OutOfMemoryError`s inside the compiler's error printer.
- `kotlin.daemon.useFallbackStrategy=false` — a crashed Kotlin daemon **fails** the build instead of
  silently recompiling in-process.
- `android.nonTransitiveRClass=false` — **required here** (the app uses core's `R.string.*` as bare
  `R.string.*`). Core deliberately omits it.
- Configuration cache and build cache are on.

---

## 7. CI

Actions is enabled on both repos. Workflows (this repo, `.github/workflows/`):

| Workflow | Trigger | Does |
|---|---|---|
| `android.yml` | push `main` / tags `v*`, PR, manual | **verify-pin** (bump PRs only), **check** (unit tests + lint), **build** (signed release APKs + GitHub Release on `v*` tags) |
| `i18n.yml` | PR, push `main`, manual | baseline ratchet, number-locale + overflow, pseudolocale inspection of a built APK |
| `pr-checklist.yml` | `pull_request_target` | enforces the PR-template checkboxes |
| `release-notes.yml` | release published | rewrites the release body from `CHANGELOG.md` |
| `automerge-core-bump.yml` | after Android CI | merges a clean `bump/core-*` pin PR |

Core repo workflows: `build.yml` (tests, lint, androidTest compile, **Room schema equality**),
`i18n.yml` (validate strings, generated-locales freshness, the rest), `publish.yml` (tag `core-*` →
gate → publish to the Maven repo → GitHub release), `release-core.yml` (manual button to tag),
`bump-consumers.yml` (opens the pin PR on this repo).

### Secrets

| Repo | Secret | Needed for |
|---|---|---|
| `SportioTV` | `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | signing CI release APKs |
| `SportioTV` | `OWNTV_EDGE_MASTER` / `OWNTV_EDGE_KEY` | optional; metadata proxy |
| `SportioTV_Core` | `CONSUMER_BUMP_TOKEN` | publishing + the pin PR (used as `PAGES_TOKEN`) |

Without the keystore secrets CI still runs tests and lint and can build a debug APK.

---

## 8. Cutting a release

1. Add a `## v1.0.1 — <date>` section to `CHANGELOG.md` (bullet points only — it becomes the release
   body and the in-app "What's new").
2. Create and push the tag `v1.0.1`. CI computes the version, builds the **signed** `standard` and
   `x86_64` release APKs, and publishes the GitHub Release with:
   - `Sportio-v1.0.1.apk` (arm)
   - `Sportio.apk` (stable "latest" copy)
   - `Sportio-x86_64-v1.0.1.apk`
3. The in-app updater reads `trenntl/SportioTV` releases and picks the APK that matches the
   device ABI (assets are matched on `.apk` and the `x86_64` marker).

**Always-latest download:** `https://github.com/trenntl/SportioTV/releases/latest/download/Sportio.apk`

### Releasing core

1. Bump `extra["coreVersion"]` in core's `build.gradle.kts` and add a `## core-x.y.z` CHANGELOG section.
2. Run the **Release core** workflow (or push the tag `core-x.y.z`). It gates, publishes
   `tv.own.owntv:core` / `:player-core` to the Sportio Maven repo, and creates the release.
3. That opens a pin-bump PR on this repo; `automerge-core-bump.yml` merges it once CI is green.

> The pin must equal core's newest release, and `tools/i18n/locales.json` must match core's copy — the
> `verify-pin` job checks both.

---

## 9. Installing on a device

Grab the signed APK from the [latest release](https://github.com/trenntl/SportioTV/releases/latest).

- **Fire TV** — install *Downloader* (AFTVnews), enter the release URL, allow unknown sources.
- **Android TV / Google TV** — Downloader from Play, or sideload via *Send files to TV*, USB, or
  `adb install Sportio.apk`.

Minimum Android 8.0 / API 26. Sportio installs **beside** upstream OwnTV: own package, own data, own
launcher entry.
