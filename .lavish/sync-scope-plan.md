# Skip-Sync: per-section Off state for IPTV sources

**Status:** approved product direction; **Rev 6 pivot (2026-07-23): Off is a sync + visibility scope, not a deletion.** Implemented.
**Visual version:** `.lavish/sync-scope.html` (predates the pivot — regenerate before sharing visually).
**Target app:** OwnTV (Android TV, Compose, Room, WorkManager).

> **Rev 6 direction.** Turning a section Off means: (1) future syncs never fetch it, and (2) the app never *shows* that source's rows for that section. It does **not** delete catalog rows or user data. This removes the hardest, riskiest parts of the earlier plan — per-source purge locks, park-then-clear crash windows, exact-key user-data deletes, pending-restore choreography, launcher orphan-cleanup races. The only tradeoff: Off does not reclaim storage. Worth it — the user-visible behavior (Off sections stop syncing and disappear) is identical, and the feature is far safer.

---

## 1. Goal & the gap today

Let a user mark a source's **Live / Movies / Series** section as **Off** so OwnTV never fetches it — no time, no bandwidth — and it disappears from the app. Today the choice is only *now* vs *later*, never *never*.

The add/re-sync flow already models three content types as `SyncContentTypes(live, movies, series)`, but a checkbox only routes a type to the foreground (**now**) or the background remainder (**later**):

- `runImport()` passes checked types as the foreground *priority* pass; `SyncContentTypes().remainderAfter(priority)` = everything **not** checked, enqueued to `CatalogSyncWorker`. The remainder always covers the full catalog — nothing is ever skipped.
- `SettingsViewModel.resync()` (`:754`) and `ShellViewModel.checkAutoRefresh()` (`:168`) enqueue with the default `SyncContentTypes()` = all-true, so even a "later" choice is forgotten on the next sync.
- Nothing is persisted: the three booleans live only in `AddSourceScreen`'s local state; `SourceEntity` stores no per-section scope.

The three sections **already exist** as first-class UI (`MainSection.LIVE_TV/MOVIES/SERIES`), each with its own browse screen + view-model, and Browse already funnels through a per-profile source-id filter (`ActiveProfileSources`, see §4-V). So this is a **persisted flag + a sync-scope subtraction + a visibility filter** — not a new content pipeline and not a deletion feature.

---

## 2. Core model (the sync side)

Three scopes, one derivation:

| Scope | Lifetime | Meaning |
|---|---|---|
| **enabledScope** | **persisted** on `SourceEntity` | Which sections may *ever* sync / show. Off = `false`. |
| **priorityScope** | transient, first-import only | now (foreground) vs later (remainder). Never stored. |
| **effectiveScope** | derived per sync call | `requested ∩ enabledScope`, then constrained by source type. Used at every sync boundary. |

```kotlin
// SyncStatus.kt
fun SyncContentTypes.intersect(other: SyncContentTypes) = SyncContentTypes(
    live = live && other.live, movies = movies && other.movies, series = series && other.series,
)

/** enabled - priority: the background remainder AFTER the foreground pass (enabled-relative now). */
fun remainderAfter(priority: SyncContentTypes) = SyncContentTypes(
    live = !priority.live && live, movies = !priority.movies && movies, series = !priority.series && series,
)

/** M3U/backup are single-stream (live-only) regardless of the persisted flags. */
fun SyncContentTypes.constrainedTo(type: SourceType) = when (type) {
    SourceType.M3U, SourceType.LOCAL_BACKUP -> copy(movies = false, series = false)
    else -> this
}

/** True iff this pass covered every enabled+constrained section. Compare against enabledFor, NOT enabledOf. */
fun SyncContentTypes.isCompleteFor(target: SyncContentTypes): Boolean = intersect(target) == target

companion object {
    /** Raw persisted enabledScope (Off = false). */
    fun enabledOf(s: SourceEntity) = SyncContentTypes(s.syncLive, s.syncMovies, s.syncSeries)
    /** enabledScope constrained by source type — the completion TARGET (M3U → live-only). */
    fun enabledFor(s: SourceEntity) = enabledOf(s).constrainedTo(s.type)
}

/** The single derivation used at every sync boundary: request ∩ enabled, then type-constrained. */
fun SyncContentTypes.effectiveFor(source: SourceEntity): SyncContentTypes =
    intersect(SyncContentTypes.enabledOf(source)).constrainedTo(source.type)
```

`effectiveFor` subsumes the existing `trackedContentTypes` source-type switch (SyncManager:60-65, CatalogSyncWorker:55-61) **and** the enabled-scope intersection — one place for the constraint. **Completion compares `effective` to `enabledFor`, never `enabledOf`**: for M3U `enabledOf` is all-true but `effective` is live-only, so `isCompleteFor(enabledOf)` would be false forever (the source never marks synced). `enabledFor` applies the same type constraint so target and pass line up.

---

## 3. Proposed UX

Segmented control per section replacing the binary "Sync first" toggles:

- **Add source:** `Now` / `Later` / `Off` — Now = foreground pass · Later = background remainder · **Off** = never fetched, never shown; persisted.
- **Edit source:** `On` / `Off` (now-vs-later only matters during the first import).
- **Interaction (locked):** single focusable row; **D-pad ◀/▶ cycles**. One focusable node per row. Section title: **"What to sync."**
- **Re-enable is instant:** because cache is retained, flipping a section back On shows its old rows immediately; a scoped resync then refreshes them.

Guard: keep "at least one section On" — `Start Import` / Save disabled if all-Off.

**Model the control as one enum per section, not overloaded booleans** — `Later` and `Off` collapse together the moment they share a boolean:
```kotlin
enum class SyncScopeChoice { Now, Later, Off }   // per section (Edit uses only On=Now / Off)

// derive once at the add/edit boundary:
val enabledScope  = SyncContentTypes(live != Off, movies != Off, series != Off)   // persisted
val priorityScope = SyncContentTypes(live == Now, movies == Now, series == Now)   // transient (first import)
```
`enabledScope` → `SourceEntity.syncLive/Movies/Series`. `priorityScope` → the foreground pass. Persisted booleans stay pure "enabled" flags; nothing downstream re-derives now-vs-later from them.

---

## 4. Correctness fixes (sync side)

With a section Off, the current gates misfire. These are required regardless of the visibility layer.

### 4a. HIGH — completion & purge gates are hardcoded to all-true
`markSynced` only stamps `lastSyncAt` when `contentTypes == SyncContentTypes()` (**SyncManager.kt:93**); `SourceRepository` only purges user-data orphans on that same all-true condition (**SourceRepository.kt:99**). With Movies/Series Off, a Live-only sync is the *complete* enabled catalog yet never equals all-true → the source stays "fresh: null" forever (re-takes the fresh-import fast path, no stale-row prune). Replace both with `effective.isCompleteFor(target)` where `target = enabledFor(source)` and `effective = contentTypes.effectiveFor(source)`.

```kotlin
// SyncManager.kt:93  — was: if (contentTypes == SyncContentTypes())
if (effective.isCompleteFor(target)) sourceDao.markSynced(source.id, now)
// SourceRepository.kt:99 — was: contentTypes == SyncContentTypes()
val purge = result is SyncResult.Success && effective.isCompleteFor(target)
```
(The snapshot orphan-purge stays snapshot-scoped and content-gated. Because Off content is *retained*, its favorites still resolve and are never purged. Safe.)

### 4b. HIGH — the intersected scope must feed the actual sync, not just tracking
`SyncManager` computes `trackedContentTypes` but passes **raw** `contentTypes` to the syncers (**SyncManager.kt:84,87**); `CatalogSyncWorker` passes raw input to `sync()` (**CatalogSyncWorker.kt:68**). A job enqueued before an edit turned a section Off would still fetch it. Compute `effective` once at the top of `SyncManager.sync` and use it for the syncer call, progress counters, and completion gate.

### 4c. HIGH — Edit path drops the flags
`ManageSourcesScreen` discards the Xtream sync booleans on edit (`{ …, _, _, _, isDefault -> }`, **ManageSourcesScreen.kt:156**); `SettingsViewModel.updateSource` has no scope params (**SettingsViewModel.kt:496**). Thread the enabled set through edit → `updateSource` → `existing.copy(syncLive = …, syncMovies = …, syncSeries = …)`.

### 4d. MEDIUM — Stalker add is hardcoded live-now / rest-later
`SetupWizard.kt:123` (`onStartStalker = { …, _ -> vm.startStalker(...) }`) and the settings `addStalker` path hardcode the staged split. Thread the same `SyncScopeChoice` through `startStalker`/`addStalker`.

### 4e. MEDIUM — migration registration + schema + tests
Bumping `OwnTVDatabase` alone is insufficient: register `MIGRATION_16_17` in **`DatabaseModule.kt:20`** (currently ends at `MIGRATION_15_16`), regenerate the exported schema JSON (`app/schemas/…/17.json`), update the migration test.

### 4f. MEDIUM — an empty effective scope is a clean no-op
A stale enqueue can resolve to nothing (Movies *later*-remainder queued, then Movies turned Off before it runs → `effective` empty). The worker stamps `markSynced` **blindly** on `completesInitialSync` (**CatalogSyncWorker.kt:76-79**). Fix: when `!effective.hasAny`, `publishStarting()`/`flush()` for a clean pill, **skip the syncer calls, do NOT stamp `lastSyncAt`**, return `Result.success()`.

### 4g. MEDIUM — reconcile completion on scope edit
Live *Now* + Movies *Later*, then Movies turned Off before the remainder runs: the foreground pass covered `{live}` while `enabledFor` was `{live, movies}`, so it never stamped; the now-empty remainder no-ops (§4f) → `lastSyncAt` stays null forever. Fix: on any scope-changing edit, **cancel the source's in-flight sync (`cancelSync`, CatalogSyncScheduler.kt:48) and enqueue a scoped resync with `enabledFor(source)`** when scope changed or `lastSyncAt == null`. That resync stamps via `isCompleteFor` and repopulates any newly-On section. **No lock needed** — nothing is destructively purged; a straggler write from the cancelled worker just lands cached rows that are hidden anyway.

### 4-V. HIGH (the new core work) — hide Off sections at every read boundary
Off must remove a source's rows for that section from **all** surfaces while leaving them in storage. Browse already funnels through one choke point: **`ActiveProfileSources`** (`core/repository/ActiveSources.kt`) holds the profile's full `SourceEntity` list and exposes `sourceIds`; every browse VM maps it to `Ctx(profileId, sourceIds)` and feeds `sourceId IN (:sourceIds)` queries. Add per-section accessors and route each consumer to the right one:

```kotlin
// ActiveProfileSources — `sources` already carries full SourceEntity, so flags ride along for free
val liveSourceIds   get() = sources.filter { it.syncLive   }.map { it.id }
val movieSourceIds  get() = sources.filter { it.syncMovies }.map { it.id }
val seriesSourceIds get() = sources.filter { it.syncSeries }.map { it.id }
```

Consumers (verified) and what each must use:

| Read site | Today | Change |
|---|---|---|
| `LiveViewModel.kt:146-150` | `aps.sourceIds` | `liveSourceIds` |
| `MovieViewModel.kt:92-93` | `aps.sourceIds` | `movieSourceIds` |
| `SeriesViewModel.kt:97-98` | `aps.sourceIds` | `seriesSourceIds` |
| `EpgViewModel.kt:103-106,553` | `aps.sourceIds` / `activeSourceIds` | `liveSourceIds` (EPG is live-only) |
| `CustomizeViewModel.kt:56-57,98` | `aps.sourceIds` (per `type`) | the accessor matching the `type` argument |
| `SearchViewModel.kt:88-89,174` | one `sourceIds` for all types | **split per type** — channels←live, movies←movie, series←series |
| `HomeViewModel.kt:280` | `activeSourceIds(...).toSet()` (mixed) | per-row: continueMovies←movie, continueSeries←series, recent/favorite live←live |
| `ShellViewModel` sidebar `hasMovies`/`hasSeries` (`:73-75`) | content-presence | AND with the section flag so an Off section drops from nav even with cache present |
| Launcher publish (`launcherIntegrationRepository.refreshProfile`, `CatalogSyncWorker.kt:88`) | reads continue/recent | verify it derives its source set from the same section-filtered path; filter Watch Next / Recent Live by the flags |
| Favorite/continue **counts** if surfaced (`FavoriteDao.count:23` is profile+type, **no source filter**) | unfiltered | any section badge must count through a source-filtered query |

Add a section-aware one-shot too: `activeSourceIds(settings, sourceDao, profileId, section)` (Home uses the one-shot form). The one-shot currently reads `sourceIdsForProfile` (ids only); the section variant needs the entities — reuse `observeForProfile(pid).first()` and filter by the flag.

---

## 5. Behavior: current vs target

| Entry point | Today | With skip-sync |
|---|---|---|
| Add source | All 3 always sync. | Now = foreground · Later = remainder · Off = never enqueued. enabledScope saved to `SourceEntity`. |
| Manual re-sync | Always full catalog. | Passes `enabledOf(source)` → syncs only On sections. |
| Auto-refresh | Always full catalog. | Passes `enabledOf(source)` → Off sections untouched. |
| Background worker | Syncs raw `contentTypes`. | `SyncManager` derives `effectiveFor(source)`; a stale enqueue can't revive an Off section. |
| Completion / purge gates | Only when pass `== SyncContentTypes()`. | `effective.isCompleteFor(enabledFor(source))`. |
| Turn a section Off | n/a | **No deletion.** Future syncs skip it; every read boundary hides that source's rows for the section; cache + user data retained. Launcher/profile refreshed. |
| Turn a section back On | n/a | Cached rows show **immediately**; a scoped resync refreshes them. User data was never touched, so progress/favorites reappear intact. |
| Existing sources (migration) | n/a | All 3 default On → identical to today. |

---

## 6. Implementation plan

1. **Persist scope on `SourceEntity`** (`core/database/entity/ProfileEntities.kt`) — add `syncLive/syncMovies/syncSeries: Boolean = true`. Now-vs-later is NOT stored.
2. **DB v16 → v17 migration** (`OwnTVDatabase.kt`) — three `ALTER TABLE sources ADD COLUMN … NOT NULL DEFAULT 1`, mirroring v13→v14 `sources.mac`. **Plus §4e**: register in `DatabaseModule.kt:20`, regen schema JSON, update migration test.
3. **`SyncStatus.kt` — core model** (§2): add `intersect`, `constrainedTo`, `isCompleteFor`, `enabledOf`, `enabledFor`, `effectiveFor`; keep `remainderAfter`.
4. **Centralize `effectiveFor` in `SyncManager.sync`** (§4b) — feed the syncers `effective`; stamp `markSynced` via `effective.isCompleteFor(enabledFor(source))` (§4a); empty `effective` → clean no-op (§4f).
5. **Purge gate** in `SourceRepository.sync:99` (§4a) — `purge = success && effective.isCompleteFor(enabledFor(source))`.
5b. **Worker empty-scope short-circuit** (§4f) — `CatalogSyncWorker`: empty `effectiveFor(source)` → publish/flush, `Result.success()`, no `markSynced` (`:76`).
6. **Add-source flow** — `SyncScopeChoice` per section → derive `enabledScope` (persist) + `priorityScope` (foreground). Remainder = `enabledScope.remainderAfter(priorityScope)`, not full-catalog remainder.
7. **Re-sync & auto-refresh honor scope** — `SettingsViewModel.resync:754` and `ShellViewModel.checkAutoRefresh:168` pass `enabledOf(source)`.
8. **Edit-source flow** (§4c/§4d/§4g) — save new flags; on scope change, `cancelSync(source.id)`, enqueue a scoped `enabledFor(source)` resync (also covers stranded `lastSyncAt == null`); `launcherIntegrationRepository.refreshProfile(profileId)` after save so hidden sections drop from the launcher.
9. **Hide Off sections at read boundaries** (§4-V) — add `live/movie/seriesSourceIds` accessors to `ActiveProfileSources`; route Live/Movies/Series/EPG/Customize VMs to their section accessor; split Search and Home per type; gate sidebar `hasMovies/hasSeries` on the flag; verify launcher + any section count queries use the filtered set.
10. **UI + plumbing** — `SyncScopeRow` composable (`AddSourceScreen.kt:280`) emitting `SyncScopeChoice`, shown on Edit too (`showContentToggles` currently hides it); thread through `onStartXtream`/`startXtream`/`addXtream` and `updateSource`/`ManageSourcesScreen:156`; extend companion add-form (`CompanionHtml`/`CompanionPayload`) to the three-way choice; include the flags in `BackupManager` export/import.

### Data-model diff (SourceEntity)
```kotlin
data class SourceEntity(
    // …existing fields…
    val epgUrl: String? = null,
    // enabledScope (v17). false = never fetch AND never show this section. Cache is retained.
    val syncLive: Boolean = true,
    val syncMovies: Boolean = true,
    val syncSeries: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long? = null,
)
```

---

## 7. Locked decisions
- **Off = stop syncing + hide everywhere, keep cache.** No catalog purge, no user-data deletion.
- Control interaction: **segmented, D-pad ◀/▶ cycles**; enum `SyncScopeChoice`.
- Re-enable shows cached rows immediately, then a scoped resync refreshes.
- v1 source types: **Xtream + Stalker** (M3U is live-only via `effectiveFor`).

## 8. What the pivot deletes (vs the earlier deletion-based plan)
Consciously dropped — do **not** reintroduce:
- turn-Off catalog purge / per-type `clearSourceContent` variant;
- pending user-data parking for Off (`parkSnapshot` / `removeSnapshotRowsInCurrentTransaction`);
- exact-key favorite/history/progress removal on turn-Off;
- the source-keyed sync/purge lock and `cancelSync + observeSync` quiesce-before-purge;
- park-then-clear crash-window handling, persist-Off-after-clear ordering, and "resolvePending skip Off" concerns.

## 9. Risks & edge cases
- **Read-boundary completeness is the whole game** — an Off section leaks if any surface reads unfiltered ids. Enumerated set in §4-V: Live/Movies/Series/EPG/Customize/Search/Home/sidebar-nav/launcher/counts. Miss one → phantom rows or counts.
- **Counts vs lists** — favorite/history lists already filter by `sourceIds` (`snapshotFavoritesManual`), but `FavoriteDao.count:23` is source-blind; any section badge must count through a filtered query.
- **Storage not reclaimed** — Off keeps cached rows on disk (accepted tradeoff). A separate opt-in "clear cached data" action can be added later without touching this feature.
- **Stale cache on re-enable** — cached rows show instantly and may be outdated until the scoped resync finishes; acceptable and expected. The scoped resync is enqueued on re-enable (§4g).
- **`isCompleteFor(enabledFor)`, not equality, not `enabledOf`** — a lingering `== SyncContentTypes()` reintroduces "never fresh" (§4a); comparing against raw `enabledOf` breaks M3U (all-true target vs live-only pass).
- **Empty effective scope = clean no-op** (§4f); **reconcile `lastSyncAt` on edit** (§4g).
- **All-Off stays impossible** — guard on Add; block Save on Edit if all-Off.
- **Backup round-trip** — include the three flags in `BackupManager` export/import.
- **M3U single-stream** — live-only via `effectiveFor`; scope UI is Xtream/Stalker only.

## 10. Open item
- Wording only: section title "What to sync"; options "Now / Later / Off".

---

## Verification notes (checked against current code, rev 6)
- **Read choke point:** `ActiveProfileSources` (`core/repository/ActiveSources.kt:21-63`) carries the full `SourceEntity` list + `sourceIds`; consumers: `LiveViewModel:146`, `MovieViewModel:92`, `SeriesViewModel:97`, `EpgViewModel:103,553`, `CustomizeViewModel:56`, `SearchViewModel:88,174`, `HomeViewModel:280` (one-shot `activeSourceIds`). Content DAO lists are all `sourceId IN (:sourceIds)` (e.g. `SeriesDao.kt:80-166`, `ChannelDao.kt:62-78`).
- **Sync-side gates:** completion all-true `SyncManager.kt:93`; syncers fed raw `contentTypes` `:84,:87`; source-type switch `:60-65`. Purge all-true `SourceRepository.kt:99`. Worker raw input `CatalogSyncWorker.kt:68`, blind stamp on `completesInitialSync` `:76-79`, post-sync launcher refresh `:88-92`.
- **Edit plumbing:** flags dropped `ManageSourcesScreen.kt:156`; no scope params `SettingsViewModel.updateSource:496` (`existing.copy` untouched `:503-512`); Stalker add `SetupWizard.kt:123`; `addXtream:537-558` already takes flags (priority only, unpersisted). Per-source cancel `CatalogSyncScheduler.kt:48`.
- **User data retained (nothing deleted):** FK to profile only, not content `UserDataEntities.kt:10-13,18-24`; count is source-blind `FavoriteDao.count:23`; lists source-filtered via `snapshotFavoritesManual`.
- **Sidebar nav presence:** `ShellViewModel.kt:73-75` (`hasMovies`/`hasSeries`) — must AND with the flag. Home already filters empty rows `HomeScreen.kt:386-404`.
- **Migration:** registered through 15_16 `DatabaseModule.kt:20-35`; DB v16 `OwnTVDatabase.kt:87`, pattern `MIGRATION_13_14` (`sources.mac`) `:376`.
