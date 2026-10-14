# Live TV

> **DPTV-Player's product.** Live TV is what the app is: a channel list, a guide, catch-up and time-shift,
> recording, multiview, and a player that keeps a hostile IPTV stream on screen. The Sports Hub
> ([SPORTS_HUB.md](SPORTS_HUB.md)) is the second feature; everything else is support.

Engine internals (mpv vs ExoPlayer, the fallback ladder, the watchdogs) live in
[PLAYBACK_ENGINES.md](PLAYBACK_ENGINES.md). This document covers the whole journey and the app/core
pieces that drive it.

---

## 1. Where the code lives

**App (`DPTV-Player`)** — `app/src/main/java/tv/own/owntv/features/live/`

| File | Responsibility |
|---|---|
| `LiveViewModel.kt` | ~120k. All live state, paging, zapping, EPG wiring, catch-up, recording, multiview hand-off. |
| `LiveScreen.kt` | ~87k. The three-pane layout, channel list, preview pane, HUD scaffolding. |
| `LiveStage.kt` | The Stage-rendered panes and the `ExoPreviewSurface`. |
| `LiveZapList.kt` | The *armed* zap list: CH+/CH− stepping, generations, the direct-tune anchor. |
| `LiveRailText.kt`, `ProviderTags.kt` | Rail labels and provider tags. |
| `CatchupJumpDialog.kt`, `CatchupManualTimeDialog.kt` | "Go back to…" pickers for channels without a guide. |
| `ScheduleRecordingDialog.kt` | Recording scheduling from live. |

**Core (`core-lib/`)** — `core/.../core/live/`

| File | Responsibility |
|---|---|
| `LiveQueries.kt` | Paging sources and counts for the channel list (`LiveKey`). |
| `LiveEpgReader.kt` | The shared now/next reader, the archive/catch-up URL builder, list subtitles. |
| `GuideReader.kt` | The guide grid (rows, batched slices, on-now). |
| `EpgNowNext.kt`, `EpgShift.kt` | The now/next model and per-channel/global guide offset. |
| `CatchupUrl.kt`, `CatchupJumps.kt`, `CatchupContinue.kt` | Catch-up URL construction and "what next" logic. |
| `LiveTimeshift.kt` | The rewind state machine (offset, reload decision, counters). |
| `OpenStreamRegistry.kt`, `ConnectionBudget.kt`, `ConnectionLimits.kt`, `ConnectionProbe*.kt` | The stream budget and provider stream-limit measurement. |
| `WatchSession.kt` | "What is being watched" (lets background sync back off). |
| `LiveKey.kt` | The channel-list keys (All / Favorites / History / Catchup / Folder / Custom). |

**Core** — `core/.../core/timeshift/` (the local rewind buffer) and `core/.../core/recording/`.

**Player-core** — `player-core/.../player/LiveTuneController.kt` is the **single tune orchestrator**
shared by the TV and phone apps; the app's `LiveViewModel` keeps only presentation.
See [PLAYBACK_ENGINES.md](PLAYBACK_ENGINES.md).

---

## 2. End-to-end playback flow

```
LiveScreen (focus a channel)
   │  LiveViewModel.playPreview(...)                 ← muted in-pane preview, 700 ms settle
   │  LiveViewModel.ensurePlaying(channel)           ← OK pressed / fullscreen
   ▼
LiveViewModel.playChannel → LiveTuneController.start(channel, source)
   │
   ├─ LiveRouting.decide(setting, pin, drm, panelRefusesSegments)  → engine + handover policy
   ├─ startOnExo(...)  or  startOnMpv(...)
   ├─ LiveLadder.arm(...)                          → rung list + whole-tune timeout budget
   └─ watchdogs: LiveExoWatchdog (Exo) / watchMpv (mpv)
            │  failure that is "about the format"
            ▼
        LiveTuneController.advance(...)  → next rung   (each rung tried at most once)
            │  rungs exhausted
            ▼
        LiveTuneController.abandon(...)  → terminal error on the current engine
```

**One tune = one cancellable job.** Starting a tune cancels the previous one and any preview/ladder.
A deliberate zap defers the stream open by **500 ms** so a held CH+ key opens one stream, not ten.

### Engine selection — `LiveRouting.decide(...)`

Authority, highest first:

1. **DRM-protected channel** (`drmConfig != null`) → ExoPlayer **only** (mpv has no CDM).
2. **Per-channel pin** (the user's "compatibility mode" toggle) → that engine.
3. **The panel learned to refuse signed segment URLs** → mpv (only when handover is allowed and the
   channel is not pinned).
4. **The setting** — per-playlist override, else the global preference (`EXO_FIRST` is the live
   default, so normal live TV starts on ExoPlayer).

Types: `EXO_FIRST`, `MPV_FIRST` (handover allowed) and `EXO_ONLY`, `MPV_ONLY` (no handover). A pin
that contradicts an "only" setting re-opens handover for that one channel.

### The fallback ladder — `LiveLadder`

- Started on ExoPlayer: `EXO_HLS → EXO_TS → MPV_HLS → MPV_TS`.
- Started on mpv: `MPV_HLS → MPV_TS → EXO_HLS → EXO_TS`.
- **Each rung is tried at most once** — this is what makes the ladder terminate instead of
  ping-ponging between engines.
- HLS rungs are dropped when the channel has no HLS/TS sibling, or that engine already learned the
  `.m3u8` URL fails.
- A **whole-tune budget** ("Give up after": 15 / 30 / 60 s / never) is armed per tune and enforced per
  rung. A provider `429 Retry-After` is honoured; a channel that *opened then broke content* gets a
  fresh budget.
- **Request refusals** (401/402/403/429/503/509/458/407) are not "format" failures: they skip the same
  engine's other extension but may still hand over to the other engine.
- If ExoPlayer is already reconnecting a channel that *had* played, its own reconnects are left running
  instead of abandoning.

---

## 3. Channel list, zapping and the preview

**Channel list.** `LiveViewModel` builds the rail from `LiveKey` values (All / Favorites / History /
Catch-up / Folder / Custom) and pages the list (page 80, prefetch 40, max 400). Hidden items, renames
and moved categories are applied inside the paging chain.

**Zapping.** `LiveZapList` owns the *armed* list — the set the CH+/CH− keys step through. It is armed
from wherever the user came from (a category, a search, the Sports Hub's matched channels, a numeric
tune), with generation guards so a slow background rebuild cannot clobber a fresh one. `canZap` is
false with ≤ 1 channel. Wrapping is both ends.

**History.** `recordLiveHistory` is debounced 5 s and cancelled by the next tune; deliberate picks
(guide, external player) record immediately. `previousChannel` (the "back to previous channel" action)
filters to active playlists.

**Direct tune by number.** `tuneByNumber` does a two-stage source lookup with a zap-context
tiebreaker, then keeps playback immediate and rebuilds a bounded (±50) zap window around the channel.

**Preview → fullscreen promotion.** The in-pane preview is the *same* long-lived ExoPlayer instance,
muted. Pressing OK calls `expectPromotion(channel)` which marks playback as already-on-Exo **before**
the fullscreen surface is drawn — this is what avoids ~4 s of black on a 4K channel. `startOnExo`
then recognises the same URL and merely unmutes it. Leaving fullscreen calls `detach()` (keeps the
stream, stands the watchdogs down) and stops Exo entirely when the preview is off.

**Multiview.** Selected channels are tuned through `LiveViewModel.tuneTile` →
`LiveTuneController.playTile` (no ladder).

---

## 4. EPG integration

Two guides coexist: the **stored bulk guide** (`EpgDao`, XMLTV) and the **provider short-EPG API**
(Xtream `get_short_epg`, Stalker). Both are read through one shared cache in `LiveEpgReader`.

- **Now/next** for the focused channel: manual EPG match > stored bulk guide (shift-aware) > provider
  short-EPG. Cached 5 minutes. A genuine gap yields `now = null` rather than mislabelling the next
  programme.
- **Guide shift** — `EpgShift.minutesFor(channel)` plus a global offset. Stored rows keep the feed's
  clock, so lookups convert and convert back; changing the offset clears the cache.
- **The guide grid** (`GuideReader`) is read per channel and in batched slices. **A bulk whole-window
  query was deleted on purpose** — it produced ~349k rows / ~100 MB and an OOM. Do not reintroduce it.
- **List subtitles** (`nowPlayingFor`) do one stored-guide batch, then a **cache-only** second pass —
  never a network call, because a bulk fetch blanked every row.
- **Catch-up URLs** (`LiveArchiveUrls`): Stalker uses `create_link`; Xtream/M3U build a timeshift URL.
  Archives are **always `.ts`** — "prefer HLS" deliberately does not apply, because archive servers
  have no HLS repackager.
- **"Go back to…"** (`CatchupJumps`) gives guide-free wall-clock offsets (15 m … 7 d) and an exact
  day + HH:MM picker for channels that publish no guide. Both paths share the archive machinery.
- **After a catch-up programme ends** (`CatchupContinue`) the app decides: next programme, go live, or
  stop. A stalled replay goes live if the edge is more than 3 minutes away.

---

## 5. Connection budget, stream limits and recording

Providers cap concurrent streams, and users get furious when the app spends them blindly.

- **`OpenStreamRegistry`** is the shared claims register (`Claim(id, sourceId, purpose)`), purpose
  `WATCHING` or `RECORDING`. Multiview tiles and recordings claim here.
- **`ConnectionBudget`** compares claims against the source's `maxConnections`. Watching is allowed
  while `total < max`; recording reserves one slot for watching by default.
- **Where the limit comes from:** Xtream *publishes* it (trusted); M3U and Stalker are **measured** by
  opening streams (`ConnectionProbe`: trickle-read ~32 KB/s, ~120 s budget, max 4 probes). Each slot is
  confirmed with two *different* channels before the failure is believed, so one broken channel never
  caps a playlist. Measured once per playlist, only when nothing is playing.
- **Fullscreen playback deliberately does NOT claim.** It is tracked by `WatchSession` instead, so it
  never spends the multiview/recording budget. **Do not unify these two** — the separation is
  intentional.
- **Recording** respects the budget through `RecordingManager.canRecordOn` / `RecordingEngine`.
- **Single-session providers** (some panels allow only one connection) are learned per host
  (`LiveStreamQuirks`) and hold the preview pane back so it cannot steal the session.
- **Multiview** caps background tiles at 720p and keeps exactly one tile audible; a device decoder
  exhaustion is reported as a *device* limit, never as a provider limit.

---

## 6. Catch-up and time-shift

Two independent features:

**Provider catch-up / rewind.** Uses the provider's timeshift server (see §4). Per-channel depth
`catchupDays` is capped at 31 and defaults to 7. Archives play on **mpv** with `isArchive = true`
(mid-GOP tolerant), and a per-host "archive needs software decoding" verdict is remembered.

**Local time-shift buffer (the `:timeshift` module).** For a live channel that has **no** provider
archive: `TimeshiftManager → TimeshiftDownloader → TimeshiftSession → TimeshiftServer`.

- Storage is **internal only** (`noBackupFilesDir/timeshift`), never USB, and cleared at process start.
- One connection, three supported shapes: **MPEG-TS** (cut into 2 s keyframe-aligned pieces),
  **HLS with fMP4** (`#EXT-X-MAP`), and **single-representation DASH**.
- It deliberately **refuses** (and the channel simply plays normally): encrypted HLS, separate-audio
  HLS/DASH, protected DASH, WebM DASH, non-TS/fMP4 bodies, and unschedulable DASH.
- Segments are identified by **number, never URL** — provider URLs carry signed tokens.
- A loopback HTTP server (`127.0.0.1:<ephemeral>`, token in the path) serves the buffer.
- The saved copy is the channel's **only** provider connection, so Exo/mpv must be released first.
- `LiveTimeshift.canRewind` is true only with a provider archive **or** a local copy.

---

## 7. `LiveViewModel` — key state and actions

Public `StateFlow`s include: `selectedKey`, `searchQuery`, `previewChannel`, `previewArmed`,
`previewBlockedSingleSession`, `nowNext`, `zapGuide`, `archiveNowNext`, `timelineProgrammes`,
`liveOnExo`, `catchupActive`, `catchupPlayer`, `watchingWallMs`, `timeshiftOffsetSec`, `canRewindLive`,
`recordingChannelIds`, `playerRecording`, `multiviewSelection`, `canZap`, `zapChannels`, `zapListTitle`,
`nowPlaying` / `nowProgrammes`, `favoriteIds`, `recentlyWatched`, `railItems`, `count`, `channels`
(`PagingData`), `providerNames`, `playlistMarks`, `livePreviewEnabled`, `liveView`, `liveLayout`,
`externalPlayerOn`, `previousChannelTarget`, `sortMode`, `showChannelNumbers`, `browserCategories`.

Main actions: `playPreview`, `watchFullscreen`, `watchFromGuide`, `ensurePlaying` /
`ensurePlayingById` / `ensurePlayingByIdAsync`, `zap` / `zapTo`, `tuneByNumber`, `previousChannel`,
`onChannelFocused`, `toggleForceMpv`, `playExternal`, `playCatchupProgramme`, `playCatchupExternal`,
`jumpBackTo`, `playCatchupAt`, `rewindLive` / `forwardLive` / `scrubLive`, `goToLive`,
`resumeTimeshift` / `dismissTimeshiftResume`, `catchupJumpOptions`, `catchupProgrammes`,
`togglePlayerRecording` / `recordNow` / `stopRecordingOn` / `scheduleRecording`,
`addToMultiview` / `clearMultiviewSelection` / `tuneTile`, `toggleFavorite`, `hideChannel` /
`renameChannel`, `setEpgMatch` / `setEpgShift` / `currentEpgShift`, `ensureNowPlaying` /
`refreshNowPlaying`, `loadChannelsForCategory` / `showCategories`, `stopPreview`, `onFullscreenExited`,
`lastWatchedLiveChannel`, `sourceOf`, `createCustomCategory` / `moveToCategory` / `hideCategory`.

The ViewModel is large. When working in it, search by action name rather than reading top to bottom.

---

## 8. Known gaps

1. **SmoothStreaming (`.ism`) is not routed** — deliberately, because the Media3 smooth-streaming
   artifact is not a dependency.
2. **Local time-shift refuses several stream shapes** (listed in §6) by design.
3. **Provider short-EPG is only a few hours** and is **not written to the database**; its synthetic
   rows cannot be looked up by id later.
4. **List subtitles never fetch** — the second pass is cache-only on purpose (see §4).
5. **`WatchSession` and `OpenStreamRegistry` are intentionally not unified** (§5).
6. **`ConnectionLimits` marks a failed measurement as "attempted"**, so a playlist the probe cannot
   answer for is not re-measured on the next sync.
7. **Multiview** opens at most 2 tiles at once (`MIN/MAX_MULTIVIEW_TILES = 1/4`); the only decoder
   controls are the 720p background cap and reporting the limit in the tile.
8. **Dead remnants after the `LiveTuneController` refactor** — `LiveViewModel` still imports
   `LiveExoWatchdog` and documents constants that now live in the controller. Harmless; do not treat
   the comments as current.
9. **No `TODO`/`FIXME` markers** exist in the live code — gaps are documented here and in the code
   comments that explain the *why*, not in TODO lines.
