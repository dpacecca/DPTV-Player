# Playback engines (mpv & ExoPlayer)

> DPTV-Player runs **two playback engines** behind one interface, picks one per tune, and hands over when
> the first cannot play the stream. This document is the engine layer — for the user-facing journey see
> [LIVE_TV.md](LIVE_TV.md).

All of this lives in the **core** repo, module `:player-core`
(`core-lib/player-core/src/main/java/tv/own/owntv/player/`), plus a few stores in
`core/.../core/player/`.

---

## 1. The two engines

| | **ExoPlayer** (Media3) | **mpv** (libmpv / FFmpeg) |
|---|---|---|
| Class | `LivePreviewEngine` | `OwnTVPlayer` |
| Strength | fast start, HLS, **DRM** (Widevine/ClearKey), live closed captions | widest codec/container set, DTS/TrueHD, re-reads HLS playlists for fresh signed tokens, catch-up/timeshift archives |
| Default for | Live TV (fast start) | compatibility fallback, all archives |
| CDM | yes | no |

Both sit behind `PlaybackEngine` (`PlaybackEngine.kt`). `PlaybackEngines.kt` builds them;
`LiveEnginePool.kt` caps background multiview tiles.

The `LivePreviewEngine` is a **long-lived, process-wide instance**. It is the muted in-pane preview,
the promoted fullscreen player (same instance, unmuted) and the base for multiview tiles. Fullscreen
live on Exo is **not** a second engine.

---

## 2. Routing — `LiveRouting.decide(...)`

Inputs in descending authority:

1. **DRM** (`channel.drmConfig != null`) → ExoPlayer only.
2. **Per-channel pin** (`ForceMpvStore`, keyed `sourceId:mediaType:remoteId`) → that engine.
3. **Panel refuses signed segments** (`LiveStreamQuirks.refusesSegments`) → mpv.
4. **The setting** — per-playlist `liveEnginePreference`, else the global preference.

`LivePreviewEngine.routeFor(...)` independently chooses the container for Exo: **HLS / DASH /
PROGRESSIVE**, from the channel's declared `manifestType`, learned panel redirects, the inferable
`.m3u8`/`.mpd` extension, and a last-resort `directSource`. Learned redirects outrank Media3's guess.

---

## 3. The fallback ladder — `LiveLadder.kt`

- Start engine ExoPlayer → `EXO_HLS → EXO_TS → MPV_HLS → MPV_TS`
- Start engine mpv → `MPV_HLS → MPV_TS → EXO_HLS → EXO_TS`
- `EXO_ONLY` / `MPV_ONLY` keep only that engine's two formats.
- **Each rung runs at most once** — the finiteness guarantee that stops engine ping-pong.
- HLS rungs are dropped when the channel has no HLS sibling, or the engine already learned the
  `.m3u8` fails.
- A **whole-tune budget** is armed per tune and enforced per rung (`startAlarm`). A provider
  `429 Retry-After` postpones it; a channel that opened then broke gets a fresh budget.
- Only `LiveTuneController.advance(...)` climbs the ladder, and only from a watchdog. Format lessons
  are recorded only when the failure was *about the format*; request refusals
  (401/402/403/429/503/509/458/407) skip the same engine's other extension but may still hand over.

---

## 4. Watchdogs

| Watchdog | Watches | Threshold |
|---|---|---|
| `LiveExoWatchdog` | Exo open timeout, no video, refused segments, post-first-frame stalls | open 12 s (+ pre-roll); stall handoff at the engine's death verdict + 2 s; +3 s grace while data still arrives |
| `watchMpv` (in `LiveTuneController`) | mpv open ("opened" = decoded picture or spinner cleared, never `isPlaying`) | 35 s |
| `LivePreviewEngine` internals | stall / freeze / open, 8-attempt reconnect ladder (~35 s, capped 2 min), 60 s healthy to earn the budget back | engine-owned |
| `NoFrameWatchdog` | shared "connected but no picture" timer for the other Exo engines | 12 s at load, 25 s software mid-GOP catch-up |

Every watchdog is gated on `stillOurs()` so a stale tune cannot abort a new one.

**Handover mechanics.** mpv→Exo waits for the mpv release plus the surface handoff; Exo→mpv waits for
the Exo release (only the remainder of 500 ms not already elapsed). The mpv handoff is dispatched to a
separate job so it cannot cancel itself. Stalker re-resolution is installed as a reconnect hook on both
engines.

**Lesson persistence.** `LiveStreamQuirks` is session-only, except the "archive needs software
decoding" verdict (`ArchiveDecodeStore`). Settings → "Forget learned stream fixes" clears them.

---

## 5. Pinning ("compatibility mode")

- `ForceMpvStore` (core) stores a per-channel engine pin under
  `enginePinKey = sourceId:mediaType:remoteId` — stable across re-syncs for all three source types.
  Legacy URL-key pins are migrated on read.
- Both directions are stored (pin-to-mpv **and** pin-to-Exo).
- The on-screen toggle (`LiveTuneController.toggleEngine`) pins the *current* channel and arms the
  current tune on the chosen engine only; the next tune reads the pin.
- **VOD pinning is separate** (`VodEngineStore`) with the same key scheme; live and VOD pins coexist.

---

## 6. Files worth knowing

| File | What |
|---|---|
| `PlaybackEngine.kt` | The interface both engines implement. |
| `PlaybackEngines.kt` / `OwnTVPlayer.kt` | mpv engine. |
| `LivePreviewEngine.kt` | ExoPlayer engine (also preview + multiview base); `routeFor`, DRM, reconnects. |
| `LiveEnginePool.kt` | Multiview tile engine pool + the 720p background cap. |
| `LiveTuneController.kt` | The single tune orchestrator: `start`, `advance`, `abandon`, `preview`, `playTile`, `toggleEngine`, handover, timeshift. |
| `LiveLadder.kt` | The rung list and budget. |
| `LiveRouting.kt` | Engine decision. |
| `LiveStreamQuirks.kt` | Learned per-host/per-channel facts (single session, segment refusal, archive needs software). |
| `LiveExoWatchdog.kt`, `NoFrameWatchdog.kt` | The watchdogs. |
| `MpvLiveBuffer.kt`, `LiveTuneController` tuning | Live buffer/audio tuning. |
| `LiveDiagnosticsLog.kt` / `PlaybackErrorLog.kt` | The exportable diagnostics the app surfaces. |

---

## 7. Rules when changing this layer

- **Never** let a rung repeat — that is the only thing preventing infinite engine ping-pong.
- **Never** hand over a DRM channel — mpv has no CDM.
- Treat request refusals differently from format failures; conflating them burns the ladder.
- Keep the `LivePreviewEngine` instance shared. Creating a second one for fullscreen reintroduces the
  black-screen-on-promotion problem.
- Multiview tiles must keep claiming through `OpenStreamRegistry` — see
  [LIVE_TV.md §5](LIVE_TV.md#5-connection-budget-stream-limits-and-recording).
