# Core architecture

DPTV-Player Core is the data and playback half of DPTV-Player. This document maps the packages and the paths
data takes. For the two user-facing features see the **app** repo's
[`docs/LIVE_TV.md`](https://github.com/dpacecca/DPTV-Player/blob/main/docs/LIVE_TV.md) and
[`docs/SPORTS_HUB.md`](https://github.com/dpacecca/DPTV-Player/blob/main/docs/SPORTS_HUB.md).

---

## 1. The two modules

| Module | Namespace | Purpose |
|---|---|---|
| `:core` | `tv.own.owntv.core` | Everything headless: database, sync, parsers, EPG, live data, recording, backup, settings, strings. |
| `:player-core` | `tv.own.owntv.player` | The playback engines and everything around them. Depends on `:core`. |

Published together under one version: `tv.own.owntv:core` and `tv.own.owntv:player-core`.
`player-core` also depends on `tv.own.owntv:libmpv`, the native engine built by **upstream**
`ahXN00/OwnTV_libmpv` — this fork does not rebuild it.

**Core renders nothing.** `compose-runtime` is the only Compose artifact (a handful of files use
`Immutable`, `remember`, `LaunchedEffect`, `CompositionLocalProvider`). Adding `compose-ui`,
`foundation`, `androidx.tv.*` or navigation would break that invariant.

---

## 2. Package map

```
core/src/main/java/tv/own/owntv/core/
├── database/    Room: entities, DAOs, migrations, FTS4 search, bulk inserts
├── sync/        source sync orchestration + the WorkManager jobs
│   ├── local/   device-to-device "local sync" (discovery, pairing, merge rules)
│   └── work/    CatalogSyncWorker, EpgSyncWorker, TrendingRefreshWorker, …
├── parser/      M3U, XMLTV, Xtream JSON — all streamed, never fully buffered
├── epg/         guide ingest/matching/shift, catch-up URL construction
├── live/        ★ the live query layer and everything live-data
├── recording/   recording scheduling, rules, engines, notifications
├── timeshift/   the local rewind buffer (download → pieces → loopback server)
├── metadata/    TMDB metadata, trending, image handling
├── settings/    the settings store (DataStore) and preference models
├── backup/      `.own` backup/restore, crypto, device identity, cross-device transfer
├── repository/  source + catalogue repositories, source tester
├── nav/         MainSection enum + visibility rules
├── i18n/        LocaleStore, AppLocale, LocalizedContent, generated SupportedLocales
├── player/      small live/VOD preference stores (engine pins, timeshift flags, decode verdicts)
├── theme/       DptvPalette + the shared palette/accents used by both apps
├── stalker/     Stalker/Ministra client, auth, EPG loader, stream resolver
├── network/     HTTP client, DNS config, proxy, connectivity observer
├── storage/     media folders/roots, document volumes
├── content/     FTS-backed search readers, air dates
├── customize/   category ordering, rename rules, bulk rename sessions
├── subtitles/   OpenSubtitles client + account store, subtitle charset
├── download/    offline download engine + notifications
├── profile/     profiles, avatars, PIN
├── companion/   the phone-handover web companion (HTML rendering)
├── catalog/, drm/, home/, launcher/, menu/, model/, reminder/, trending/, tv/, ui/, update/, util/, weather/, brand/
└── …
```

---

## 3. The database

Room + Paging 3 + FTS4. `core/schemas/` holds one JSON schema per version and **is a CI gate**: CI
runs `git diff --exit-code -- core/schemas` after the build, so a schema change must ship a committed
schema file and a migration.

- Entities cover channels, categories, favourites, history, EPG programmes, recordings, reminders,
  profiles, downloads, metadata, trending, sync state, tombstones and more.
- **FTS4** backs search.
- **Paging** with a bounded page size keeps memory flat across ~50k-channel catalogues.
- Migrations are explicit; `OwnTVDatabaseMigrationTest` exercises them.

---

## 4. Sync and parsing

- **M3U** is line-streamed; **Xtream JSON** is read with `JsonReader`; a huge provider payload is never
  fully buffered.
- Refresh is **chunked, cancellable and reports progress** (`SyncStatus`, `SyncProgressText`).
- **Section gate.** Every syncer (M3U, Xtream, Stalker) is handed the source's enabled content types
  (Live / Movies / Series). A section switched off is **skipped**, and for M3U its old rows are cleaned
  up after a successful parse, so a source with only Live TV on syncs noticeably faster. M3U follows its
  switches like the others; only a local backup source is always live-only.
- The schedulers live in `sync/work/` (WorkManager). Background jobs **yield to playback** by observing
  `WatchSession`.

---

## 5. EPG

- **XMLTV** is stream-parsed (gzip-aware) into a rolling window and pruned.
- **Matching** between channels and guide entries (`EpgMatcher`, `EpgAutoMatcher`), with per-channel
  manual overrides and a per-source/per-channel **shift** (`EpgShift`) because feeds publish in their
  own clock.
- **Catch-up URLs** (`CatchupUrl`) are built per source type (Xtream timeshift, M3U conventions,
  Stalker `create_link`). Archives are always `.ts` — providers' archive servers do not repackage HLS.
- Guide reads go through `LiveEpgReader` / `GuideReader` — see §6.

---

## 6. The live layer (`core/.../core/live/`)

This is the data half of Live TV. The behaviour is documented in the app repo's
[`docs/LIVE_TV.md`](https://github.com/dpacecca/DPTV-Player/blob/main/docs/LIVE_TV.md); this is
the map.

| Area | Files | Notes |
|---|---|---|
| Channel queries | `LiveKey`, `LiveQueries` | Keys: All / Favorites / History / Catchup / Folder / Custom; paging + counts. |
| Now/next | `LiveEpgReader`, `EpgNowNext`, `EpgShift` | One shared cache (5 min). Manual match > stored bulk (shift-aware) > provider short-EPG. A gap yields `now = null`, never a mislabel. |
| Guide grid | `GuideReader` | Per-channel rows + batched slices. **A bulk whole-window query was deleted after a ~349k-row / ~100 MB OOM — do not reintroduce it.** |
| Catch-up | `CatchupUrl`, `CatchupJumps`, `CatchupContinue` | URL building, guide-free "go back to…" offsets, and what to do when a catch-up programme ends. |
| Time-shift | `LiveTimeshift` (+ `core/timeshift/`) | The rewind state machine; the local buffer lives in `core/timeshift/`. |
| Connection budget | `OpenStreamRegistry`, `ConnectionBudget`, `ConnectionLimits`, `ConnectionProbe`, `ConnectionProbeRun` | Claims per source, watching vs recording; Xtream publishes the limit, M3U/Stalker are measured. |
| Watching signal | `WatchSession` | Lets background work back off. Deliberately **not** the same as the budget. |

> **Fullscreen playback does not claim against `OpenStreamRegistry`** — it is tracked by `WatchSession`.
> Multiview tiles and recordings do claim. Do not unify the two; the separation is what keeps
> fullscreen from spending the multiview/recording budget.

---

## 7. Settings, backups, strings

- **Settings** live in a DataStore-backed store (`settings/SettingsRepository`). The **UI language** is
  the one exception: it is a `SharedPreferences`-backed `LocaleStore` in `core/i18n/`, because it must
  be read synchronously in `attachBaseContext`. See the app repo's `docs/I18N_RUNTIME.md`.
- **Backup** is a single `.own` file (optionally encrypted), plus a device-to-device transfer over
  Wi-Fi. The format is versioned; treat a change as breaking and document it in the core changelog.
- **Strings** are entirely here. `tools/i18n/README.md` is the authoritative guide;
  `tools/i18n/locales.json` is the catalogue the app copies.

---

## 8. Playback (`:player-core`)

Two engines behind one `PlaybackEngine` interface: **`LivePreviewEngine`** (ExoPlayer/Media3 — fast
HLS, DRM) and **`OwnTVPlayer`** (libmpv — widest codecs, archives). `LiveTuneController` is the single
tune orchestrator shared by the TV and phone apps; `LiveLadder` walks the fallback rungs; watchdogs
recover a hostile stream.

The full treatment is in the app repo's
[`docs/PLAYBACK_ENGINES.md`](https://github.com/dpacecca/DPTV-Player/blob/main/docs/PLAYBACK_ENGINES.md).

---

## 9. Versioning and publishing

- `coreVersion` lives in `build.gradle.kts` (`extra["coreVersion"]`). Both modules share it.
- Release by tag `core-x.y.z` (must equal `coreVersion`, and `CHANGELOG.md` must have a section).
  `publish.yml` gates on the tests and the i18n checks, publishes both AARs to the DPTV-Player Maven repo
  (`gh-pages`), then creates the GitHub release.
- The release opens a **pin-bump pull request** on the app repo (`bump-consumers.yml`), which the app
  merges itself once CI is green.

See the app repo's `docs/BUILD_AND_RELEASE.md` for the full release flow.
