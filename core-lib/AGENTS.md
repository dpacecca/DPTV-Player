# AGENTS.md — DPTV-Player Core

> **Single entry point for any agent working in this repository.** Read it first, then open the doc
> you need. The deep dives live in [`docs/`](docs/) and in the **app** repo
> (`DPTV-Player`, which is where Live TV and the Sports Hub are documented).

---

## 1. What this repository is

**DPTV-Player Core** is the headless half of DPTV-Player — a fork of
[OwnTV Core](https://github.com/ahXN00/OwnTV_Core). It holds everything that is **not a screen**:

- the Room database and the full migration chain,
- source sync and parsing for **M3U / Xtream / Stalker**,
- **EPG** ingest, matching and catch-up URL building,
- the **live query layer**, the connection budget, local time-shift,
- recording, downloads, profiles, backup/restore,
- **every user-visible string**,
- and the **two playback engines** (libmpv and ExoPlayer).

Two Android libraries, published together as one version:

| Module | Namespace | What |
|---|---|---|
| **`:core`** | `tv.own.owntv.core` | database, sync, parsers, EPG, backup, settings, metadata, strings |
| **`:player-core`** | `tv.own.owntv.playercore` | the playback engines, routing, fallback ladder, watchdogs |

**This repo renders nothing.** `compose-runtime` is the only Compose artifact allowed — no
`compose-ui`, no `foundation`, no `androidx.tv.*`, no navigation. That is what keeps it reusable.

---

## 2. How the app uses it

The **`DPTV-Player`** app repo depends on this library as a **pinned Maven artifact**
(`tv.own.owntv:core` / `:player-core`). Core is published to its own public Maven repo
(`core-lib/ (built in-tree)`, the `gh-pages` branch of this repo).

For local work on both at once, the app sets `owntv.corePath` in `~/.gradle/gradle.properties` and
**composite-builds** this repo from source. CI in the app repo leaves it unset and uses the pin.

**Hooks.** Core deliberately knows nothing about the app. The app assigns these in its
`Application.onCreate` (before Koin starts):

| Hook | Supplies |
|---|---|
| `CoreBuildInfo` | versionName, versionCode, edgeKey, devTools, debug, diagnosticBuild, **releaseRepo** |
| `CrashRecorder.diagnostics` | the live diagnostics log |
| `LiveSessionLimit.report` | per-provider stream quirks |
| `SubtitleFontAssets.resourceOf` | the app's bundled subtitle fonts |
| `AppIconSwitcher.mainActivityClass` | the app's `MainActivity` |
| `WatchSession` (from the playback screen) | which playlist is on screen |

---

## 3. Golden rules

1. **Core never imports from an app.** The arrow is one-way: `app → :player-core → :core`.
2. **Core stays UI-framework-neutral.** `compose-runtime` only.
3. **Every user-visible string lives here**, in `core/src/main/res/values*/strings*.xml` — never
   inline in Kotlin, never in the app. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
   `tools/i18n/README.md`.
4. **`tools/i18n/locales.json` is authoritative here.** The app carries a copy that the
   `bump-consumers` flow keeps in sync; never hand-edit the app's copy.
5. **Keep the Room schema stable.** CI compares `core/schemas/` after the build; a schema change means
   a new migration **and** a committed schema file.
6. **Keep the gates green** (below) before calling anything done.

---

## 4. Quick start

**Requires JDK 21** and an Android SDK (`android-37.2` platform, build-tools 36+). Python 3 for the
i18n checks.

```bash
./gradlew :core:assembleDebug :player-core:assembleDebug
./gradlew :core:testDebugUnitTest :player-core:testDebugUnitTest
./gradlew build                     # everything, including lint
```

Instrumentation tests need a device or emulator — **never run them against a real TV holding real
data**: installing a test APK wipes the catalogue, playlists, profiles and history.

---

## 5. Where things live

```
core/src/main/java/tv/own/owntv/core/
├── database/     Room: entities, DAOs, migrations, FTS4 search        (44 files)
├── sync/         M3U / Xtream / Stalker sync + the work scheduler     (29)
├── parser/       M3U, XMLTV, Xtream JSON — all streamed               (6)
├── epg/          guide ingest, matching, catch-up URLs, shift         (10)
├── live/         ★ the live query layer, now/next, catch-up, time-shift,
│                 the connection budget, WatchSession                  (15)
├── recording/    recording scheduling + engines                      (15)
├── timeshift/    the local rewind buffer                             (8)
├── metadata/     TMDB metadata and trending                          (14)
├── settings/     the settings store (DataStore) + preference models   (9)
├── backup/       `.own` backup/restore + cross-device transfer        (6)
├── repository/   source / catalogue repositories                      (7)
├── nav/          MainSection enum + visibility rules                  (2)
├── i18n/         generated supported-locale catalogue                 (3)
├── player/       live/VOD preference stores (engine pins, flags)      (17)
├── theme/        DptvPalette and the shared palette/accents
├── util/, network/, storage/, model/, content/, customize/, drm/,
│   download/, home/, launcher/, menu/, profile/, reminder/, stalker/,
│   subtitles/, trending/, tv/, ui/, update/, weather/, companion/, catalog/, brand/
└── …

player-core/src/main/java/tv/own/owntv/player/
└── ★ PlaybackEngine, OwnTVPlayer (mpv), LivePreviewEngine (ExoPlayer),
      LiveTuneController, LiveLadder, LiveRouting, watchdogs, timeshift glue
```

The **live + player-core** internals are documented in the app repo:
[`docs/LIVE_TV.md`](https://github.com/dpacecca/DPTV-Player/blob/main/docs/LIVE_TV.md) and
[`docs/PLAYBACK_ENGINES.md`](https://github.com/dpacecca/DPTV-Player/blob/main/docs/PLAYBACK_ENGINES.md).

---

## 6. Gates

| Gate | Command |
|---|---|
| Unit tests | `./gradlew :core:testDebugUnitTest :player-core:testDebugUnitTest` |
| Lint | `./gradlew lint` |
| Instrumentation **compiles** | `./gradlew :core:assembleDebugAndroidTest` |
| Room schemas unchanged | `git diff --exit-code -- core/schemas` |
| Hardcoded literals | `python tools/i18n/check_hardcoded_strings.py verify --bootstrap` |
| Strings validate | `python tools/i18n/validate_strings.py --report text` |
| Locales fresh | `python tools/i18n/gen_supported_locales.py check` |
| Number locale | `python tools/i18n/check_number_locale.py` |
| Text overflow | `python tools/i18n/check_text_overflow.py` |
| i18n tool tests | `python tools/i18n/test_i18n_tools.py` |
| Weblate byte-format | `python tools/i18n/check_weblate_format.py check` (needs `requirements-weblate.txt`) |

The Gradle `verifyI18nLiterals` task also runs the literal check at `preBuild`.

> **In this fork, run the Python checks with UTF-8** (`PYTHONUTF8=1` on Windows) or the tool tests
> fail on a `charmap` codec error — that is an environment artifact, not a code problem.

---

## 7. Documentation map

| If you are working on… | Read |
|---|---|
| The data layer, packages, Room schema, sync, EPG | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Strings, the catalogue, translations | [`tools/i18n/README.md`](tools/i18n/README.md) — **authoritative** |
| Live TV / playback behaviour | app repo: `docs/LIVE_TV.md`, `docs/PLAYBACK_ENGINES.md` |
| Publishing a core version | [`README.md`](README.md) → Versioning, and the app repo's `docs/BUILD_AND_RELEASE.md` |

---

## 8. Pitfalls

- **Room schema equality is a CI gate.** Adding/changing an entity means a new migration **and** a
  committed `core/schemas/…json`. The build fails otherwise.
- **`coreVersion` lives in `build.gradle.kts`**; the `core-x.y.z` tag must match it, and
  `CHANGELOG.md` must have a section for it, or the publish workflow refuses.
- **Strings must be complete in every packaged locale** before a string change lands — the validators
  enforce schema, placeholders and plural completeness.
- **`.github/workflows/translations.yml` does not exist in this fork.** Core is still the home of the
  translations, but the daily Weblate sync is upstream's; `tools/i18n/sync_translations.py` is present
  but unwired here. Do not assume a scheduled translation sync runs.
- **`android.nonTransitiveRClass` is deliberately NOT set here** — it belongs to the consuming app.
  Inside this repo `:player-core` imports `tv.own.owntv.core.R` explicitly.
- **`compileSdk` is `release(37) { minorApiLevel = 2 }`** — API 37 exists only as the 37.2 canary
  platform. Do not lower it.
