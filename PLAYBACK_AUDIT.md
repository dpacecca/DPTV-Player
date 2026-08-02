# OwnTV Playback Backend — Deep-Dive Audit

**Date:** 2026-08-02
**Baseline commit:** `f76fb8c` "Fix live playback on single-session providers and mpv A/V drift" (branch `main`, clean tree)
**Scope:** every code path where a stream is opened, decoded, rendered, watched, retried or torn down —
both engines (libmpv and Media3/ExoPlayer), all three source types (Xtream, M3U, Stalker), all content
types (Live TV, Movies, Series, catch-up/timeshift, live rewind, downloads), and all four presentation
modes (fullscreen, Live preview pane, docked mini-player, audio-only).

> **Status of this document:** findings are derived from reading the current repository code. Every
> claim carries a `file:line` reference so it can be re-checked. Where a conclusion is a *hypothesis*
> that still needs device confirmation, it is labelled **(hypothesis)**. Nothing here has been changed
> in the code yet — this is the audit and the plan.

---

## Table of contents

1. [How the playback system is actually built](#1-how-the-playback-system-is-actually-built)
2. [The complete settings tree and which settings touch playback](#2-the-complete-settings-tree-and-which-settings-touch-playback)
3. [The gate matrix — what each setting really does, ON vs OFF](#3-the-gate-matrix--what-each-setting-really-does-on-vs-off)
4. [Findings](#4-findings)
5. [The three reported issues, diagnosed](#5-the-three-reported-issues-diagnosed)
6. [Source-type specifics: Xtream / M3U / Stalker](#6-source-type-specifics-xtream--m3u--stalker)
7. [Upstream engine versions and upgrade opportunities](#7-upstream-engine-versions-and-upgrade-opportunities)
8. [The plan, in phases](#8-the-plan-in-phases)
9. [Verification matrix](#9-verification-matrix)
10. [Review of the last commit (f76fb8c)](#10-review-of-the-last-commit-f76fb8c)

---

## 1. How the playback system is actually built

### 1.1 Two engines, five engine *instances*

| Instance | File | Backend | Used for |
|---|---|---|---|
| `OwnTVPlayer` | `player/OwnTVPlayer.kt` (3297 lines) | libmpv | The main player. Fullscreen VOD, fullscreen Live (when routed to mpv), catch-up, live rewind, downloads, audio-only. Owns the surface, HUD state, tracks, subtitles, watchdogs. |
| `LivePreviewEngine` | `player/LivePreviewEngine.kt` (1608 lines) | ExoPlayer | The **default Live TV engine**. Runs the preview pane *and* is promoted to fullscreen for live. |
| `ExoSubtitleEngine` | `player/ExoSubtitleEngine.kt` (675 lines) | ExoPlayer | VOD on ExoPlayer: the image-subtitle handoff, the "Movies & Series player = ExoPlayer" mode, and the automatic VOD fallback after mpv fails. Driven *through* `OwnTVPlayer`. |
| `HeroPreviewEngine` | `player/HeroPreviewEngine.kt` (151 lines) | ExoPlayer | Home-screen hero trailer/preview. Muted, looping, no watchdogs, no fallback. |
| `ExternalPlayerLauncher` | `core/player/ExternalPlayerLauncher.kt` | — | Hands the URL to VLC/MX/etc. via an Intent. |

`PlaybackEngine` (`player/PlaybackEngine.kt`) is the common interface the HUD talks to, with adapters
for `OwnTVPlayer` and `LivePreviewEngine`.

### 1.2 The engine ladders

**Live TV (from the Live screen):**

```
LiveViewModel.playChannel()
├── external player enabled for Live?  → Intent, done
├── channel pinned to mpv (ForceMpvStore / "compatibility mode")? → startOnMpv
└── startOnExo  →  previewEngine.play(resolveStreamUrl(url, source))
                   └── watchExoOutcome() races:
                         · noVideoDetected            → fallbackToMpv
                         · segmentsRefused (2 × 403)  → fallbackToMpv
                         · state == ERROR             → fallbackToMpv
                         · audioUnsupported (after 300 ms) → fallbackToMpv
                       fallbackToMpv → player.stop() → 500 ms → OwnTVPlayer.play(isLive = true)
```

**VOD (Movies / Series):**

```
OwnTVPlayer.loadUrl(isLive = false)
├── per-item pin (VodEngineStore) or "Movies & Series player" setting decides the first engine
├── mpv first (default):
│     mpv fails terminally → fallbackToExoVod()  [gated by audioCodecSafeForExo()]
└── ExoPlayer first (setting/pin):
      Exo fails → fallbackToMpvVod() → mpv gets its full retry ladder
```

**Catch-up / live rewind:** always mpv, always `preferSoftware = true`
(`LiveViewModel.kt:1475`, `:1536`, `EpgViewModel.kt:313`) — archive segments start mid-GOP and the
hardware decoder can wedge on them.

**Downloads:** `DownloadsViewModel.kt:115` → `player.play(path, isLive = false)`, plain mpv on a local file.

### 1.3 mpv's render path

* Direct/zero-copy: `vo=mediacodec_embed` + `hwdec=mediacodec` (`OwnTVPlayer.kt:467-472`).
* GL rescue: `vo=gpu` + `hwdec=no` (pure software decode).
* **There is no middle rung.** `hwdec=mediacodec-copy` (hardware decode → GL compositing) was
  deliberately removed (`OwnTVPlayer.kt:463-466`). This matters — see [F09](#f09).

### 1.4 mpv's watchdog ladder

| Watchdog | Constant | Applies to | Action |
|---|---|---|---|
| T_OPEN | no `FILE_LOADED` | VOD | one silent hard-reset + retry, then error |
| MOOV-AT-END | `fileLoaded && height==0 && !bitrate && >6 s` (`:1980-1990`) | VOD | **immediate dead-end error, no fallback** |
| T_DECODE | `fileLoaded && >7 s` (`:1990`) | VOD | hard reset |
| Decode watchdog | `DECODE_CHECK_MS = 4 s` (`:244`) | both | logs stats; retry → software → error |
| Decode guard | `enforceDecodeGuard()` (`:2864`) | both | aborts >1080p on a software decoder |
| Surround runaway | `SURROUND_CHECK_MS = 7 s` (`:243`, `:3021`) | **VOD only** | latch stereo + reload |
| Live freeze / no-video | `LIVE_STALL_POLL_MS` loop (`:2005`) | Live | bounded reconnect |
| Live open timeout | `LIVE_OPEN_TIMEOUT_MS` (`:2020`) | Live | error |
| END_FILE ladder | `:3131-3294` | both | `.ts`⇄`.m3u8` swap → retry → software → "vlc" UA → Exo fallback |

### 1.5 ExoPlayer live's watchdog ladder (`LivePreviewEngine`)

`STALL_MS = 12 s`, `PROGRESS_CHECK_MS = 2.5 s`, `FROZEN_LIMIT = 3`, `FREEZE_TIMEOUT_MS = 8 s`,
`NO_VIDEO_TIMEOUT_MS = 8 s`, `MAX_RECONNECTS = 8`, backoff `1.5/3/6/10/15 s`, plus
`noteSegmentRefusal()` (2 × 403/404/410 → hand to mpv) and `noteSessionLimit()` (HTTP 458 → wait 2 s, retry once).

It watches **video** thoroughly. It does not watch **audio output** at all — see [F02](#f02).

---

## 2. The complete settings tree and which settings touch playback

Settings live in two screens plus five sub-screens. Playback-relevant rows are marked ●.

### Settings home — `features/shell/components/SettingsScreen.kt`

```
Profile        → Profiles
Content        → Playlists ● (per-source: User-Agent ●, Prefer HLS ●)
                 EPG Sources, Guide logos, Customize Categories, Sidebar, CH+/- paging,
                 Browsing & lists, Home screen, Metadata (TMDB), Download folder,
                 Backup & Restore, Clear watch history
Appearance     → Theme, Accent, Glass effect, UI Zoom, Animations, Weather
Playback       → [group label at SettingsScreen.kt:469]
                 Live preview ●                     (:472)
                 └ Preview audio ●                  (:480, only when preview on)
                 Mini-player ● (size/position)      (:488 → MiniPlayerSettingsScreen)
                 HDR ●                              (:497, default ON)
                 Auto frame rate ●                  (:501, default OFF)
                 Surround sound ●                   (:509, default OFF)
                 Auto-play next episode ●           (:517)
                 Catch-up ● (timezone/offset)       (:525)
                 Video Player Settings ●            (:538 → VideoPlayerSettingsScreen)
                 Playback error log ●               (:544)
Network        → Proxy ● , DNS ●
App            → App startup, Check for updates, About
```

### Video Player Settings — `features/settings/VideoPlayerSettingsScreen.kt`

```
Hardware decoding ●
Movies & Series player ● (mpv | ExoPlayer)
External player ● (per section: Live / Movies / Series)
Default zoom ●
Resume playback ●
Subtitle appearance ● → Size / Color / Position / Background transparency
Preferred subtitle language ●
OpenSubtitles account
Preferred audio language ●
Audio sync ● (default audio delay)
Live latency ● → Low (2 s) | Balanced (device budget) | Stable (15 s) | Custom (1–60 s, default 8)
Channel numbers (direct tune)
Measured stream stats ●
```

### Per-item, not in Settings

* **Force mpv** per live channel (`ForceMpvStore`, keyed by stream URL) — HUD gear.
* **Engine pin** per movie/episode (`VodEngineStore`, keyed by `enginePinKey(sourceId, type, remoteId)`).
* **Zoom**, **A/V-sync nudge**, **audio/subtitle track**, **subtitle delay** — HUD, per session.

### Defaults (`features/settings/data/SettingsRepository.kt`)

| Setting | Default | Line |
|---|---|---|
| HDR | **ON** | `:949` |
| Auto frame rate | **OFF** (force-reset once in 4.1.6) | `:960-978` |
| Surround sound | **OFF** | `:692` |
| Hardware decoding | ON | `:615` |
| Measured stream stats | ON | `:635` |
| Live latency | Balanced (force-reset once in 4.1.6) | `:1039-1063` |
| Prefer HLS (per Xtream source) | **OFF** | `ProfileEntities.kt:54` |

---

## 3. The gate matrix — what each setting really does, ON vs OFF

This is the core of the audit. **A ✗ in the "Exo" column means the setting silently does nothing on
that engine.**

| Setting | mpv (`OwnTVPlayer`) | ExoPlayer live (`LivePreviewEngine`) | ExoPlayer VOD (`ExoSubtitleEngine`) | Hero preview |
|---|---|---|---|---|
| **Surround sound** | ✔ `audio-channels=auto-safe`/`stereo` + `audio-format=s16` + `48 kHz` (`:486`, `:1539-1544`) | **✗ not referenced at all** | **✗ not referenced at all** | ✗ (muted) |
| **HDR** | ✔ `target-colorspace-hint` (`:697`, `:1603`) | **✗** | **✗** | ✗ |
| **Auto frame rate** | ✔ `FrameRateController` + `Surface.setFrameRate` (fullscreen only) | ✔ `setVideoChangeFrameRateStrategy` + `FrameRateController` — **but fps can be null**, see [F14](#f14) | ✗ (no AFR wiring) | ✗ |
| **Hardware decoding** | ✔ `hwdec`/`vo` (`:443`,`:467`) | **✗** | ✗ (`softwarePreferred` is set by the *rescue path*, never by the setting — `:224`, `:405`) | ✗ |
| **Live latency** | ✔ `demuxer-readahead-secs` per live load (`:520`) | **✗ for raw `.ts`** — only `MediaItem.LiveConfiguration.targetOffsetMs`, which HLS/DASH honour and progressive ignores (`:1448-1455`); the `LoadControl` is hardcoded 8/10 s (`:1493`) | n/a (VOD uses `budget.cacheSecs`) | ✗ |
| **Measured stream stats** | ✔ gates bitrate tracking | ✔ gates bitrate **and fps measurement** — and fps feeds AFR ([F14](#f14)) | ✔ bitrate only | ✗ |
| **Prefer HLS** (Xtream) | ✔ *only* when the URL came through `LiveViewModel` | ✔ same | n/a | ✗ |
| **Movies & Series player** | ✔ `vodPreferExo` (`:718`, `:1855-1862`) | n/a | ✔ | n/a |
| **External player** | ✔ short-circuits before either engine | ✔ | ✔ | n/a |
| **Audio sync (default delay)** | ✔ `audio-delay` (`:1599`) | ✗ (HUD hides the control for live: `PlayerHud.kt:572-574`) | ✗ | ✗ |
| **Subtitle appearance** | ✔ mpv `sub-*` options | n/a | ✔ `StyledSubtitleView` | n/a |
| **Preferred audio/sub language** | ✔ `alang`/`slang` | ✗ | ✔ (track selector defaults) | ✗ |
| **Volume boost >100 %** | ✔ `volume-max=150` (`:1554`) | **✗ capped at 100** | ✗ | n/a |
| **Zoom** | ✔ | ✔ | ✔ | ✗ |

### Mode matrix

| | Fullscreen | Live preview pane | Docked mini-player | Audio-only |
|---|---|---|---|---|
| Engine | mpv or Exo | Exo only | whichever is playing | whichever is playing |
| AFR | ✔ (`isFull && autoFrameRate`, `OwnTVShell.kt:646,649`) | ✗ by design | ✗ by design | ✗ |
| mpv subtitle overlay | ✔ (`OwnTVShell.kt:652`) | n/a | **✗** — subs vanish when docked | n/a |
| Watchdogs | ✔ | ✔ | ✔ | ✔ |
| Audio-only implementation | — | — | — | mpv `vid=no` (`:2544`) / Exo `clearVideoSurface()` (`LivePreviewEngine.kt:1140`) |

---

## 4. Findings

Severity: **S1** = users lose playback / wrong content plays · **S2** = a setting lies or a whole
provider class is broken · **S3** = quality/diagnosis gap · **S4** = polish.

---

### F01 — Surround Sound does nothing on ExoPlayer, and Live TV runs on ExoPlayer — **S1**

`surroundSound` is read and applied **only** in `OwnTVPlayer` (`:486`, `:703-712`, `:1539-1544`).
It appears nowhere in `LivePreviewEngine.kt`, `ExoSubtitleEngine.kt` or `HeroPreviewEngine.kt`.

Both ExoPlayer engines are built with a stock `DefaultRenderersFactory` and a stock `DefaultAudioSink`
(`LivePreviewEngine.kt:1501`, `ExoSubtitleEngine.kt:403-406`). Media3's default behaviour is:

* query `AudioCapabilities` from the HDMI/EDID + `AudioManager`;
* if the sink claims AC-3 / E-AC-3 / DTS support, **select the passthrough "decoder" and bitstream the
  compressed 5.1 track untouched** (this is even documented in OwnTV's own comment at
  `LivePreviewEngine.kt:79-82`);
* otherwise decode and render up to the sink's channel count.

Consequences, all of which match the reported symptoms exactly:

1. **"I turned Surround sound OFF and nothing changed."** Correct — on Live TV the toggle is inert.
2. **A TV that *claims* AC-3/DTS support over EDID but mishandles it** gets a bitstream it cannot play:
   picture with **no sound**, or sound that drifts. There is no capability *verification*, only the
   sink's own claim.
3. **No graceful fallback.** Nothing ever says "the sink claimed surround, it isn't working, drop to
   stereo PCM." The mpv side has a failsafe; the Exo side has none.

**The fix has three parts** (all supported Media3 API, no forks):

* `DefaultTrackSelector.Parameters.setMaxAudioChannelCount(2)` when Surround is OFF — makes Media3
  prefer a stereo track and refuse multichannel selection.
* Override `DefaultRenderersFactory.buildAudioSink()` and pass
  `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` when Surround is OFF — this is the documented way to
  force "stereo PCM only, never passthrough".
* When Surround is ON, keep real capabilities but **verify** (see [F02](#f02)).

---

### F02 — No audio-output watchdog on ExoPlayer ("picture but no sound" is invisible) — **S1**

`LivePreviewEngine` watches video exhaustively: rendered-frame counter
(`VideoFrameMetadataListener`, `:432`), position stall, `NO_VIDEO_TIMEOUT_MS`, decoder errors, load
errors, session limits, segment refusals. For audio it implements exactly one callback —
`onAudioSinkError` (`:181`) — and that callback only **stores a string for the error screen**. It
triggers no recovery.

Media3 exposes everything needed and none of it is used:

* `AnalyticsListener.onAudioPositionAdvancing(eventTime, playoutStartSystemTimeMs)` — fires when the
  audio track actually starts producing sound. If it never fires while an audio track is selected and
  video is rendering, the sink is dead.
* `onAudioUnderrun(eventTime, bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)` — repeated underruns
  are the signature of a struggling/passthrough sink (the "surround lags" case).
* `onAudioDecoderInitialized` — tells you whether the "decoder" chosen was the passthrough shim.

**Fix:** a `SilentAudioWatchdog` mirroring the existing no-video watchdog. If ~6 s after the first
rendered frame the audio position has never advanced (or underruns exceed a threshold), rebuild the
player with `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` + `maxAudioChannelCount = 2`, latch that
for the session, and toast *"This TV couldn't play the surround track — switched to stereo."* That is
literally the behaviour requested: *deliver surround when it works, fall back cleanly when it doesn't.*

---

### F03 — The mpv surround failsafe is too narrow — **S2**

`OwnTVPlayer.kt:3021-3050`. It is:

* **VOD-only** — wrapped in `if (!isLiveContent)`. Live TV on mpv has no surround failsafe at all.
* **one-shot at 7 s** — a sink that degrades later is never caught.
* **detects only a ~2× runaway** (`estimated-vf-fps > container-fps * 1.5`). The user's actual symptom
  — audio *lagging* behind video, or gradual drift — produces normal fps and is never detected.

**Fix:** run it on live too; poll periodically instead of once; add a second trigger based on
`audio-speed-correction` / repeated `audio-buffer` underflow, and expose the manual A/V-sync nudge on
live (see [F11](#f11)).

---

### F04 — "Hardware decoding = OFF" doesn't reach ExoPlayer — **S2**

`hwDecoding` gates only `targetHwdec()`/`targetVo()` in mpv (`OwnTVPlayer.kt:443,467-468`).
`ExoSubtitleEngine.softwarePreferred` is set by the *caller* as a rescue (`:224`, used from the mpv
software-fallback path), never from the setting; `LivePreviewEngine` has no software path whatsoever.

So a user whose TV has a broken hardware decoder turns the setting off, and Live TV still hardware-decodes.

**Fix:** feed the setting into both Exo engines (`setMediaCodecSelector(softwareFirstSelector)` — the
selector already exists in `ExoSubtitleEngine.kt:410`) and rebuild the player when it flips.

---

### F05 — "Prefer HLS" is applied in exactly one file; three other live entry points ignore it — **S1**

`resolveStreamUrl()` / `ChannelEntity.playStreamUrl()` (`core/database/entity/ContentEntities.kt:86-98`)
is called only from `LiveViewModel` (`:566`, `:1169`, `:1355`).

| Entry point | Applies Prefer HLS? | Engine ladder? | Force-mpv pin? | Quirk learning? |
|---|---|---|---|---|
| Live screen (`LiveViewModel.playChannel`) | ✔ | ✔ Exo→mpv | ✔ | ✔ |
| **Search** (`SearchViewModel.kt:260-275`) | **✗** | **✗ mpv only** | **✗** | ✗ |
| **Guide/EPG** (`EpgViewModel.kt:287`) | **✗** | **✗ mpv only** | **✗** | ✗ |
| **Guide catch-up** (`CatchupUrl.forSource` → `XtreamClient.timeshiftUrl`, default `ext = "ts"`) | **✗** | mpv | n/a | n/a |
| Live rewind (`LiveViewModel.kt:1563`) | ✔ (`if (source.preferHls) "m3u8" else "ts"`) | mpv | n/a | n/a |

This is the same class of bug as the one just fixed in `f76fb8c`: a provider-specific behaviour is
honoured on one path and silently dropped on the others. A user on an m3u8-only panel gets working
Live TV from the Live screen and a dead channel from Search or the Guide.

**Fix:** make a single `LivePlaybackRouter` (or move `playChannel` into a shared use-case) that
Search, the Guide, Favorites and the Home rows all call. Pass `ext` through `timeshiftUrl` from
`preferHls`.

---

### F06 — Live latency is a no-op on ExoPlayer for raw MPEG-TS — **S2**

`LivePreviewEngine.mediaSourceFor()` (`:1444-1470`) puts the user's choice into
`MediaItem.LiveConfiguration.targetOffsetMs`. Media3 honours that for **HLS and DASH only** — the
engine's own comment says *"Ignored by progressive/raw-TS sources."* The actual buffer for a raw `.ts`
comes from the `DefaultLoadControl`, which is hardcoded:

```kotlin
// LivePreviewEngine.kt:1493-1496
DefaultLoadControl.Builder()
    .setBufferDurationsMs(MIN_BUFFER_MS /*8_000*/, MAX_BUFFER_MS /*10_000*/, 1_000, 2_000)
    .setTargetBufferBytes(if (budget.lowSpec) 16 MB else 24 MB)
```

Since the vast majority of Xtream live URLs are raw `.ts`, **the Live latency setting does nothing for
most Live TV on the default engine.** On mpv it works (`OwnTVPlayer.kt:520`).

The 8/10 s narrow window is deliberate and well-reasoned (`:1476-1491`: keep the socket from idling so
a provider can't cull it). The fix is not to widen it blindly but to **drive it from the setting**:

| Preset | min / max buffer | bufferForPlayback |
|---|---|---|
| Low (2 s) | 2 000 / 4 000 | 500 |
| Balanced | 8 000 / 10 000 (today's values) | 1 000 |
| Stable (15 s) | 15 000 / 17 000 | 2 500 |
| Custom N | N·1000 / (N+2)·1000 | min(N·1000/2, 3 000) |

and rebuild the ExoPlayer instance when the preset changes.

---

### F07 — There is no "buffer N seconds before starting" control — **S3**

`bufferForPlaybackMs = 1_000` / `bufferForPlaybackAfterRebufferMs = 2_000` are hardcoded
(`LivePreviewEngine.kt:1494`). This is exactly the knob the "freezes every ~40 s, but works if I pause
4 s first" report is asking for, and it is a 20-line change once F06's plumbing exists.

**Fix:** expose "Start after buffering" (Off / 2 s / 5 s / 10 s) alongside Live latency, mapping to
`bufferForPlaybackMs` on Exo and to a `--cache-pause-initial` + `--cache-secs` pair on mpv.
Make it per-playlist-overridable (the reporter asked for per-playlist), stored next to `preferHls` on
`SourceEntity`.

---

### F08 — "MOOV-AT-END" mislabels genuine decoder failures and dead-ends them — **S1**

```kotlin
// OwnTVPlayer.kt:1980-1990
val bitrateKnown = mpv?.getPropertyString("video-bitrate")?.toLongOrNull()?.let { it > 0 } ?: false
if (fileLoaded && currentHeightPx == 0 && !bitrateKnown && elapsed > 6_000) {
    _error.value = vodErrorMessage("This video isn't formatted for streaming. Ask the provider to re-encode with fast-start, or download it first.")
    expectingPlayback = false; _buffering.value = false
    videoCheckJob?.cancel()
    return@launch          // ← no retry, no software attempt, no ExoPlayer fallback
}
```

The condition "loaded but no height and no bitrate" is also true when **the video decoder failed to
start**. When MediaCodec dies with `err 0xfffffff4` (that's `-12`, `NO_MEMORY`/insufficient resources),
mpv reports the file as loaded, height stays 0, and the user is told their provider's file is
malformed — while `errorInfo.raw` on the very same screen shows the MediaCodec error. That
contradiction is what the reporter screenshotted.

**Fix:** check `lastMpvError` for a MediaCodec/decoder signature *before* the MOOV branch. If present,
route to the decode-failure ladder (hard reset → `mediacodec-copy` → software → Exo), not to the
"re-encode your file" message.

---

### F09 — The decode-failure ladder has no rung between "direct hardware" and "software ≤1080p" — **S1**

* Software rescue is gated: `hwDecodingActive() && !glUnsupported && lastVideoHeightPx <= 1080`
  (`OwnTVPlayer.kt:3107`, `:3214`).
* `enforceDecodeGuard()` (`:2870`) actively **aborts** playback above 1080p on a software decoder.
* `hwdec=mediacodec-copy` (hardware decode, GL compositing) was removed (`:463-466`).

So a 4K file the direct path cannot open has **no rescue at all** on mpv, and `fallbackToExoVod()` is
additionally gated by `audioCodecSafeForExo()` (`:1129-1140`), which refuses the fallback for
TrueHD/DTS-HD tracks with no device decoder.

The removal of `mediacodec-copy` was justified for the *normal* path (copying 4K HDR frames is slow).
As a **rescue rung it is exactly right**: degraded playback beats an error screen, and it is almost
certainly why external mpv plays the files OwnTV refuses (mpv-android defaults to `mediacodec-copy`).

**Fix:** reinstate `hwdec=mediacodec-copy` + `vo=gpu` as an explicit *rescue* step (never the default),
inserted before the software rung, and lift the ≤1080p gate for that rung only.

---

### F10 — No pre-flight video decoder capability check — **S2**

`deviceHasAudioDecoder(mime)` exists for audio (`OwnTVPlayer.kt:1151-1155`). There is no equivalent
for video. Nothing ever calls
`MediaCodecInfo.VideoCapabilities.isSizeSupported(w, h)` / `areSizeAndRateSupported(w, h, fps)`.

Many TV SoCs (Hisense/MediaTek in particular) advertise 4K for **HEVC/VP9/AV1 only** and cap **AVC at
1920×1080**. A 3840×1608 H.264 file is therefore not decodable in hardware on those panels — which is
a *known, queryable* fact, not a mystery. Today it surfaces as a wrong error message.

**Fix:** on a decode failure (and optionally proactively from the stream's declared codec+resolution),
query the codec list; if no hardware decoder covers the size, go straight to the copy/software rung and
tell the user *"This TV's hardware can't decode 4K H.264 — trying software decoding"* rather than
blaming the file.

---

### F11 — VOD "Retry" reloads the item as a LIVE stream at position 0 — **S1**

```kotlin
// PlayerHud.kt:553
OwnTVButton("Retry", onClick = { player.retry() }, …)

// OwnTVPlayer.kt:2303-2305
fun retry() { val url = currentUrl ?: return; reloadLive(url, resetRetries = true) }

// OwnTVPlayer.kt:2314-2327  → loadUrl(fresh ?: url, …, isLive = true, startPositionMs = 0L, …)
```

The Retry button is rendered for **every** error, VOD included (`PlayerHud.kt:531-553`). Pressing it on
a movie:

* loses the resume position (`startPositionMs = 0L`);
* applies **live** demuxer options, live readahead and the live watchdog set to a finite file;
* and — worst — if a Stalker live channel was watched earlier in the session, `reconnectUrlProvider` is
  still installed (`LiveViewModel` only clears it on its own paths, `:1590`), so `reloadLive` calls
  `provider.freshUrl()`, which resolves **`_previewChannel`'s live cmd**. The user presses Retry on a
  movie and a TV channel starts playing. (See [F12](#f12).)

**Fix:** `retry()` must branch on `isLiveContent` — VOD reloads with `isLive = false` at
`_position.value`; only live goes through `reloadLive`.

---

### F12 — `reconnectUrlProvider` is a shared mutable field with the wrong lifetime — **S2**

`OwnTVPlayer.reconnectUrlProvider` (`:320`) and `LivePreviewEngine.reconnectUrlProvider` (`:357`) are
installed/cleared **only** by `LiveViewModel.setStalkerReconnect()` (`:551-556`). Meanwhile:

* Stalker **VOD** resolves its link once at play time (`MovieViewModel.kt:477-479`,
  `SeriesViewModel.kt:769-782`) and installs **no** provider. A Stalker `create_link` URL lives
  ~2–4 h; a long movie, a paused movie, or any mid-play reconnect replays a dead URL.
* The live provider can outlive the Live screen and leak into a VOD retry ([F11](#f11)).

**Fix:** make the provider part of the *load* (a field on the item being loaded), not a player-global;
install a VOD provider from `MovieViewModel`/`SeriesViewModel` for Stalker sources.

---

### F13 — mpv's direct path has no vsync-aligned presentation; 25 fps content judders — **S2**

mpv is initialised with `video-sync=audio` and `framedrop=decoder+vo` (`:1561`, `:534-535`) on
`vo=mediacodec_embed`. In embed mode mpv does not control scan-out timing — frames are handed to the
MediaCodec surface and the display presents them on its own cadence. On a fixed 60 Hz output, 25 fps
becomes an uneven 2:3:2:3 cadence — visible judder — while 50 fps maps cleanly. This is a *known*
limitation of `mediacodec_embed`, not an OwnTV bug, and **Auto frame rate is the only mitigation**
(50 Hz for 25 fps, 24/48 Hz for 24 fps: `FrameRateController.kt:107-109` already does the right
multiple selection).

AFR ships **OFF by default** (`SettingsRepository.kt:960-963`, force-reset in 4.1.6).

**Fix:** (a) surface a one-time prompt when a 24/25/50 fps stream plays on a 60 Hz output with AFR off
— "This channel is 25 fps and your TV is at 60 Hz; enable Auto frame rate?"; (b) consider
`video-sync=display-resample` for the GL rescue path only (it is a no-op on embed); (c) document the
limitation in the user guide.

---

### F14 — AFR is silently dead on ExoPlayer live when "Measured stream stats" is OFF — **S2**

```kotlin
// LivePreviewEngine.kt:320
private fun displayFps(f: Format) = f.frameRate.takeIf { it > 0 } ?: fpsSample.lastFps
// :325-328
private fun ensureFpsMeasurement() {
    if (!measuredStatsEnabled) return   // ← escape hatch
    if ((player?.videoFormat?.frameRate ?: 0f) <= 0f) restartFpsMeasurement()
}
```

Raw MPEG-TS rarely carries a declared `Format.frameRate`, so `_videoFps` depends on the **measured**
sample. Turn "Measured stream stats" off — a setting presented purely as a performance escape hatch —
and `videoFps` stays `null`, so `AutoFrameRateEffect` is fed `0f` and `FrameRateController.apply()`
returns immediately (`FrameRateController.kt:64`). AFR appears enabled and does nothing.

**Fix:** always run the (cheap, ≤5-sample) fps measurement when AFR is on, independent of the stats
toggle; gate only the bitrate/throughput tracking on the stats setting.

---

### F15 — `ExoSubtitleEngine` and `LivePreviewEngine` are configured asymmetrically — **S3**

| | live engine | VOD engine |
|---|---|---|
| `forceDisableMediaCodecAsynchronousQueueing()` | ✔ `:1501` | **✗** `:403` |
| `setEnableDecoderFallback(true)` | ✗ | ✗ |
| `MediaCodecSelector` software-first | ✗ | ✔ (rescue only) |
| Data source | `DefaultHttpDataSource` (+HLS CC factory) | `OkHttpDataSource` |
| AFR strategy | ✔ set | ✗ never set |

The async-queueing macroblock corruption on Realtek/Amlogic VPUs that the live engine works around
affects UHD-HEVC **files** just as much as channels. And neither engine enables
`setEnableDecoderFallback(true)`, so a codec that fails to initialise errors out instead of trying the
next decoder on the device (usually the software one) — a free rung on the rescue ladder.

---

### F16 — M3U per-channel HTTP options are ignored — **S1 for affected playlists**

`M3uParser.kt:92` — `line.startsWith("#") -> Unit // other directives … ignored for now`, and
`:104` — `streamUrl = line` verbatim.

Not parsed: `#EXTVLCOPT:http-user-agent`, `#EXTVLCOPT:http-referrer`, `#EXTVLCOPT:http-origin`,
`#EXTHTTP:{"cookie":…}`, `#KODIPROP:inputstream.adaptive.*`, and the pipe-suffix convention
`http://host/x.ts|User-Agent=Foo&Referer=Bar`. Only a **per-source** User-Agent exists
(`SourceEntity.userAgent`).

Playlists that require a per-channel UA/Referer — very common for restreams and for anything behind a
CDN token — return 403 in OwnTV and play fine in TiviMate/VLC. The user has no way to fix it.

**Fix:** parse the three common conventions into a `headers` column on `ChannelEntity`, and pass them
to mpv (`http-header-fields`) and Media3 (`DefaultHttpDataSource.Factory.setDefaultRequestProperties`).

---

### F17 — M3U catch-up: `catchup="append"` never appends, `{lutc}` is unsupported — **S2**

`M3uParser` reads the `catchup` type (`:86`) but `ChannelEntity` stores only
`catchup: Boolean`, `catchupDays`, `catchupSource` (`ContentEntities.kt:76-82`) — **the type is
dropped**. `CatchupUrl.forSource()` then hardcodes it away:

```kotlin
// CatchupUrl.kt:88
SourceType.M3U -> forM3u(channel.streamUrl, null /* ← catchupType */, channel.catchupSource, …)
```

so the `append` branch inside `forM3u` (`CatchupUrl.kt:114`+) is unreachable. Playlists using the extremely common
`catchup="append" catchup-source="?utc={utc}&lutc={lutc}"` build a URL that is just the query string,
and `{lutc}` isn't in `START_TOKENS`/`END_TOKENS` (`:25-26`) so it survives literally into the request.

**Fix:** persist `catchupType` (Room migration), pass it through, add `lutc`/`now`/`timenow` tokens and
the `flussonic`/`shift`/`xc` styles.

---

### F18 — Live diagnostics can never be produced by a normal user — **S3**

`LiveDiagnosticsLog.enabled = BuildConfig.DEBUG || BuildConfig.DIAGNOSTIC_BUILD` (`:22`).
Release users — i.e. everyone reporting provider-specific bugs — produce **nothing**.

`PlaybackErrorLog` (the Settings viewer) only records **hard errors**:
`if (info.reason == null && info.raw == null) return` (`:40`). Quality complaints — judder, A/V drift,
audio missing while video plays, periodic rebuffering — never write an entry. That is precisely why the
4K/25 fps report says *"nothing appears in the playback error log."*

**Fix:**
1. A hidden Settings toggle (long-press the version row, or Settings → Playback error log → "Enable
   detailed diagnostics") that flips `LiveDiagnosticsLog.enabled` at runtime in release builds.
2. A **"Report this stream"** action in the HUD that snapshots the current `PlayerDiagnostics` —
   engine, codec, resolution, fps declared vs measured, dropped frames, buffer state, audio format and
   channel count, whether passthrough was chosen, display mode/refresh rate, AFR state, all relevant
   settings — into `PlaybackErrorLog` even when nothing "failed", plus an export/share.

Without this, every provider-specific report costs a multi-round guessing game.

---

### F19 — Miscellaneous, lower severity

| # | Finding | Severity |
|---|---|---|
| F19a | Volume boost (mpv `volume-max=150`) is unavailable on Live TV/Exo — `adjustVolume` caps at 100 (`LivePreviewEngine.kt`). Inconsistent behaviour between Live and VOD. | S4 |
| F19b | mpv subtitles are not drawn in the docked mini-player — `if (isFull && !liveOnExo) SubtitleOverlay(...)` (`OwnTVShell.kt:652`). | S4 |
| F19c | Audio-only on ExoPlayer calls `clearVideoSurface()` (`:1140`) but leaves the video track selected, so the decoder keeps running with nowhere to draw. `setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)` would free the decoder and cut power. mpv does it right (`vid=no`). | S3 |
| F19d | `HeroPreviewEngine` has no watchdogs, no error surface and no fallback; on a single-session panel it can hold the account's only session while the user tries to tune. It doesn't consult `LiveStreamQuirks.isSingleSession()` the way `playPreview()` does (`LiveViewModel.kt:569`). | S3 |
| F19e | The A/V-sync nudge is hidden for live (`PlayerHud.kt:572-574`), yet mpv's `audio-delay` works perfectly on live streams. Users with a drifting live feed have no manual escape. | S3 |
| F19f | `PlayerBudget` tiers on `totalGb` only (`PlayerBudget.kt`). A 2 GB 4K TV box gets `lowSpec` (48 MiB demuxer, `profile=fast`) which is right, but a 4 GB box with a weak GPU gets the mid tier and no GL diet. Consider adding a GPU/codec-based signal. | S4 |
| F19g | `demuxerLavfOptionsFor()` (`:224-232`) returns `""` for every non-trimmed case — the `url`, `live` and `hls` parameters are unused. Dead parameters invite a future mis-edit. | S4 |
| F19h | `enforceDecodeGuard()` aborts >1080p software decode unconditionally. On a modern 8-core box, 1440p/4K H.264 software decode is sometimes viable; a measured check (actual fps vs container fps after 5 s) would be better than a fixed height gate. | S3 |

---

### F26 — The 4K-movie error code isn't in the humanization table — **S2**

`PlayerDiagnostics` already tails the app's own logcat for MediaCodec/AudioTrack failures, and
`PlayerErrors.reasonFor()` maps codes to plain English (`PlayerDiagnostics.kt:111-138`). It recognises
`0x80001000`, `0x80001001`, `0xfffffff3` (−13) and `0xffffffea` (−22) — but **not `0xfffffff4`**, which
is −12 / `NO_MEMORY`, the exact code in the 4K-movie report. The ENOMEM branch only matches the
*decimal* form:

```kotlin
// PlayerDiagnostics.kt:108
private val ENOMEM_RX = Regex("""\b(?:err(?:or)?|status|code)\s*[:=]?\s*-12\b""")
```

MediaCodec logs these as unsigned hex, so `"Codec reported err 0xfffffff4"` never matches. The reason
line comes back `null`, which is why the MOOV-AT-END text had nothing competing with it.

**Fix (one line):** add `0xfffffff4` to the ENOMEM branch — and while there, the rest of the hex errno
family, since MediaCodec always reports them this way. This alone turns that error screen from
*"ask your provider to re-encode"* into *"Device ran out of memory for the decoder"*.

---

### F27 — No audio focus handling and no MediaSession anywhere in the app — **S3**

A repo-wide search for `requestAudioFocus`, `OnAudioFocusChangeListener`,
`ACTION_AUDIO_BECOMING_NOISY` and `MediaSession` returns **nothing**. Consequences:

* OwnTV never requests audio focus, so it doesn't duck or pause when the system, the Assistant, or
  another app needs the speaker — and other apps get no signal to stop when OwnTV starts. Two apps can
  play over each other.
* With no `MediaSession`, the TV's system transport controls, Bluetooth/headset remote play-pause keys,
  and Assistant "pause" don't reach the player, and there's no now-playing card.

App **lifecycle** is handled properly, for the record — `MainActivity.onStop/onStart` (`:104-127`)
calls `onAppBackgrounded()`/`onAppForegrounded()` on both engines and stops the hero preview, with a
documented LMK rationale. It's specifically *audio focus* and *media session* that are absent.

---

### F28 — Stopping a live stream evicts the **app-wide** HTTP connection pool — **S3**

```kotlin
// LivePreviewEngine.kt:859-865  — doc comment says "this engine's now-idle sockets"
private fun releaseHttpConnections() {
    Thread { runCatching { okHttpClient.connectionPool.evictAll() } … }.start()
}
```

The injected `okHttpClient` is the **single app-wide singleton** (`di/DataModule.kt:39-72`) shared by
`HttpClient`, `XtreamClient`, `StalkerClient`, EPG downloads, TMDB metadata and Coil image loading. So
every live stop — every zap, every engine handoff, every backgrounding — throws away pooled
connections for the whole app, forcing fresh TCP+TLS handshakes for the next poster or EPG fetch.

The *intent* is right and the single-session fix needs it. The **better way** is to give the streaming
path its own pool: `okHttpClient.newBuilder().connectionPool(ConnectionPool(...)).build()` shares the
proxy/DNS/UA/protocol configuration but owns its sockets, so `evictAll()` touches only stream
connections. `LivePreviewEngine` already builds a `diagnosticHttpClient` derivative (`:1407-1409`), so
the pattern is in place.

---

### F29 — mpv treats HTTP 458 as a hard refusal, contradicting the fix that introduced it — **S2**

This one is inside the last commit itself. `LiveStreamQuirks.isSessionLimit(458)` documents 458 as
*"the stream is fine, **we** are the second client"* — the opposite of a refusal — and the ExoPlayer
side acts on exactly that: wait ~2 s for the session to free, retry once
(`LivePreviewEngine.kt:656`, `:998-1012`). But on the mpv side:

```kotlin
private val HTTP_REFUSAL_RX = Regex("""HTTP error 4\d\d""", RegexOption.IGNORE_CASE)
```

`4\d\d` matches 458, so mpv classes it with 403/404, allows `HARD_REFUSAL_MAX_RETRIES = 1`, and moves
on to the format/User-Agent fallbacks — changing the request when the request was never the problem.
On a single-session panel that is exactly backwards: the correct response is to wait and repeat the
*identical* request.

**Better way:** split the classification three ways instead of "any 4xx" —
**auth/gone** (401/403/404/410 → stop repeating, try format/UA), **busy** (408/429/458 → back off and
repeat the identical request, honouring `Retry-After` when present), **other 4xx** (current
behaviour). 429 has the same "busy" semantics as 458 and isn't special-cased anywhere today either.

---

### F30 — Xtream tells you the session limit up front and nothing reads it — **S3**

`max_connections` and `active_cons` appear **nowhere** in the codebase. Xtream's
`player_api.php` returns both in `user_info` on every login/refresh, so a one-session account is a
*known fact at sync time* — yet the app only discovers it by failing a tune and parsing a 458.

**Better way:** parse `max_connections` during account refresh, store it on the source, and seed
`LiveStreamQuirks.rememberSessionLimit()` from it at startup. The whole "first zap on a one-session
panel misbehaves, then it's fine" class of symptom disappears, and it survives app restarts, which the
session-only cache cannot. Same argument applies to persisting the learned HLS-redirect quirk per
source with an expiry rather than re-learning it every cold start.

---

### F31 — Preview suppression on a single-session panel is silent — **S4**

```kotlin
// LiveViewModel.kt:567-569
if (player.hasActiveStream && LiveStreamQuirks.isSingleSession(targetUrl)) return
```

Correct behaviour, no user-visible explanation: the user focuses channel after channel and the preview
pane simply stays dead, indistinguishable from a bug. A one-line hint in the preview pane —
*"Preview off — your provider allows one stream at a time"* — closes it.

---

## 5. The three reported issues, diagnosed

### Issue 1 — RMK62: 4K/25 fps live, A/V desync on mpv, "picture but no sound" on Exo, empty error log

| Symptom | Root cause | Finding |
|---|---|---|
| Nothing in the playback error log | `PlaybackErrorLog.log` only fires on a hard error; quality faults never record | [F18](#f18) |
| Turning Surround OFF changed nothing | Live TV runs on ExoPlayer; the surround setting is mpv-only | [F01](#f01) |
| Exo: picture, no sound (4K) | Passthrough bitstream selected for a sink that claims but cannot deliver; no watchdog, no fallback | [F01](#f01), [F02](#f02) |
| mpv: audio/video out of sync (4K) | `video-sync=audio` on `mediacodec_embed`: a 4K stream that decodes just behind real time drifts, and `framedrop=decoder+vo` can't help on the direct path. No live surround failsafe, no live A/V nudge in the HUD | [F03](#f03), [F13](#f13), [F19e](#f19--miscellaneous-lower-severity) |
| 1080p **25** fps judders, 1080p **50** fps flawless | 25 fps on a 60 Hz output via `mediacodec_embed` = 2:3 pulldown; 50 fps divides 60 Hz far more gracefully and mpv's frame drop hides the rest. AFR (→50 Hz) is the fix and is **off by default** | [F13](#f13) |
| "Prefer HLS fixed the 4K sync (but zapping got slower)" | HLS makes ExoPlayer use the HLS pipeline (proper timestamps, segment boundaries) instead of the raw-TS progressive path — and mpv full-probes HLS instead of trimming (`OwnTVPlayer.kt:495-508`), which is exactly the slower start the reporter noticed | [F06](#f06) |
| Turning AFR OFF didn't help | Correct — AFR OFF is the *problem* for 25 fps, not the cure. And with "Measured stream stats" involved, AFR ON may also have been silently inert | [F13](#f13), [F14](#f14) |

**Actions:** F01, F02, F03, F13, F14, F18, F19e.

---

### Issue 2 — BouldozeR: 4K movies fail (Hisense, Android 12), external mpv plays them

Evidence from the screenshots: engine **MPV**, VOD, `H.264 / AVC 3840x1608`, raw error
`MediaCodec: Codec reported err 0xfffffff4, actionCode 0, while in state 6/STARTED`, shown under the
message *"This video isn't formatted for streaming…"*.

`0xfffffff4` = `-12` = `NO_MEMORY` / insufficient codec resources. The chain:

1. mpv opens the file; MediaCodec is asked to decode **4K H.264** on the direct
   `vo=mediacodec_embed` path.
2. On most Hisense/MediaTek TV panels the AVC decoder is capped at 1920×1080 (4K is HEVC/VP9 only) —
   or cannot allocate buffers for a 3840×1608 surface. The codec errors.
3. mpv reports the file loaded but never produces a height or bitrate.
4. The **MOOV-AT-END** branch fires at 6 s and dead-ends with a message about the provider's file —
   while the codec error sits in the same dialog, contradicting it. [F08](#f08)
   And the app *could* have named the real cause: `PlayerDiagnostics` captured the codec line, but
   `PlayerErrors.reasonFor()` has no entry for `0xfffffff4`, so it returned `null` and left the MOOV
   text unopposed. [F26](#f26) — this is the cheapest single fix in the whole audit.
5. The software rescue is unreachable (`lastVideoHeightPx <= 1080`), `mediacodec-copy` no longer
   exists, and `fallbackToExoVod` is never reached because the branch `return`s first.
   [F09](#f09), [F10](#f10)

External mpv succeeds because mpv-android defaults to `hwdec=mediacodec-copy` with `vo=gpu` — the exact
rung OwnTV removed. **(hypothesis, high confidence — confirm by asking the reporter whether the
external player was mpv-android with default settings, and by having them try OwnTV's Movies & Series
player = ExoPlayer on the same file.)**

"Worked two versions ago" is consistent with the direct-render/no-GL-fallback change: it is the removal
of the copy path, not a regression in the file handling.

**Actions:** F08, F09, F10, F15 (`setEnableDecoderFallback`).

---

### Issue 3 — ntas-sys: some Xtream streams rebuffer every ~40 s; pause 3–4 s then play = smooth

1. **No latency preset can help.** On the default Live engine the presets only reach
   `MediaItem.LiveConfiguration`, which raw `.ts` ignores; the real buffer is the hardcoded 8/10 s
   `DefaultLoadControl`. Custom 30 s changes nothing. [F06](#f06) — this exactly matches "none of the
   presets, including manual higher seconds, fixed it".
2. **Why pause/play works.** Pausing lets the loader fill to `MAX_BUFFER_MS` and stops the drain;
   resuming then starts from a full buffer, and `bufferForPlaybackAfterRebufferMs = 2 000` is enough to
   ride over the provider's periodic hiccup. There is no way to ask for that automatically.
   [F07](#f07)
3. **What the ~40 s period probably is (hypothesis):** the load control stops reading once 10 s are
   buffered and resumes below 8 s, so the socket idles in ~2 s windows. Providers that throttle or
   re-key an idle connection produce a periodic stall whose period is set by their own timer, not ours.
   Confirming needs `LiveDiagnosticsLog` from the reporter — which they cannot currently produce.
   [F18](#f18)

**The reporter's own request — "cache but don't play before N seconds" — is the right feature**, and it
is [F07](#f07). Per-playlist, as they asked, next to `preferHls`.

**Actions:** F06, F07, F18.

---

## 6. Source-type specifics: Xtream / M3U / Stalker

### Xtream

* Live URL is always `…/live/user/pass/ID.ts` (`XtreamClient.kt:337`); `.m3u8` only via the per-source
  **Prefer HLS** toggle, applied in one file only ([F05](#f05)).
* `hlsSupported` is detected from `allowed_output_formats` (`XtreamClient.kt:364,396-404`) but
  deliberately does **not** gate `preferHls` — it only refines the Settings wording
  (`ProfileEntities.kt:50-53`). Given how many users never find the toggle, consider **auto-enabling**
  `preferHls` when `hlsSupported` is true *and* the first `.ts` tune fails on both engines, and telling
  the user it happened.
* Timeshift: path form `…/timeshift/user/pass/dur/start/ID.ts` with an automatic fallback to the PHP
  query form (`CatchupUrl.timeshiftPhpAlternate`, used at `OwnTVPlayer.kt:3166`). Good.
  But `ext` defaults to `"ts"` and the Guide path never passes `preferHls` ([F05](#f05)).
* Panel quirks are handled well and session-scoped: HLS-redirect learning, signed-segment refusal,
  HTTP 458 single-session, broken PTS (`LiveStreamQuirks.kt`). This is the strongest part of the
  codebase. Its one weakness: **nothing is persisted**, so every app start re-learns and re-pays the
  failed-tune cost. Consider persisting per-source (not per-URL) with an expiry.
* HTTP 512 on bulk `get_series` handled per-category in `SyncManager` (sync, not playback).

### M3U

* URL stored verbatim; no rewriting, no Prefer-HLS equivalent (correct — M3U URLs are whatever the
  provider wrote).
* **Per-channel HTTP options are dropped** ([F16](#f16)) — the single biggest M3U playback gap.
* **Catch-up `append` is broken and `{lutc}` unsupported** ([F17](#f17)).
* mpv's probe trimming is disabled for `.m3u8` and extensionless live URLs (`OwnTVPlayer.kt:495-508`)
  after a documented regression — correct, keep it.
* A local-file M3U import (`file://`) plays through the same path; no special handling needed.

### Stalker / MAC portal

* Every play resolves `cmd → URL` freshly via `create_link` (`StreamUrlResolver.kt:34-50`); direct-play
  cmds short-circuit. Correct.
* **Live** installs a `ReconnectUrlProvider` so a dead link is re-minted (`LiveViewModel.kt:540-556`).
* **VOD/Series does not** ([F12](#f12)) — a Stalker movie that outlives its link, or hits any
  reconnect, replays a dead URL and errors.
* The provider is a **player-global field** that Live owns; combined with [F11](#f11) it can make a
  VOD Retry play a live channel.
* Catch-up uses `type=tv_archive` + `auto /media/<id>_<start>_<dur>.mpg`
  (`StreamUrlResolver.kt:59-70`); `CatchupUrl.forSource` returns `null` for Stalker (`:89`) and the
  Stalker path is handled separately — verify the Guide's catch-up entry point routes Stalker channels
  to `resolveCatchup` and not into the `null` branch.
* Portal user-agent is carried per source (`StalkerCredentials.userAgent`) and passed to both engines.

---

## 7. Upstream engine versions and upgrade opportunities

Checked against the live repositories on 2026-08-02.

| Component | OwnTV has | Latest available | Verdict |
|---|---|---|---|
| **Media3 / ExoPlayer** | `1.10.1` (`gradle/libs.versions.toml:30`) | `1.10.1` stable; `1.11.0-rc01` on Google Maven, release notes not yet published | **Already on the newest stable.** Re-evaluate 1.11.0 when it goes final. |
| **libmpv** (`dev.jdtech.mpv:libmpv`) | `1.0.0` (`:32`) | `1.0.0` (2026-04-08) — bundles **mpv 0.41.0**, **FFmpeg 8.1**, libplacebo 7.360.1, dav1d 1.5.3, libass 0.17.4 | **Already on the newest.** |
| OkHttp | `4.12.0` (`:55`) | 4.12.0 is the last 4.x; 5.x is out | Optional; 5.x changes the datasource wiring. Not a playback fix. |

**So there is no version upgrade to be had — the wins are all in how the current engines are configured.**
Concretely, APIs already available in Media3 1.10.1 that OwnTV does not use yet:

| API | Use |
|---|---|
| `TrackSelectionParameters.Builder.setMaxAudioChannelCount(int)` | force stereo when Surround is OFF ([F01](#f01)) |
| `DefaultRenderersFactory.buildAudioSink()` + `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` | disable passthrough deterministically ([F01](#f01)) |
| `AudioCapabilities.getCapabilities(context, audioAttributes, routedDevice)` | *know* what the current HDMI route supports before choosing |
| `AnalyticsListener.onAudioPositionAdvancing` / `onAudioUnderrun` / `onAudioDecoderInitialized` | the missing audio watchdog ([F02](#f02)) |
| `DefaultRenderersFactory.setEnableDecoderFallback(true)` | free extra decode rung ([F15](#f15)) |
| `ExoPlayer.Builder.experimentalSetEnableMediaCodecVideoRendererDurationToProgressUs()` (new in 1.10.0) | dynamic frame scheduling — better frame pacing, worth an A/B on 25 fps live |
| `MediaCodecInfo.VideoCapabilities.areSizeAndRateSupported()` | pre-flight capability check ([F10](#f10)) |
| `Surface.setFrameRate(..., CHANGE_FRAME_RATE_ALWAYS)` | already used correctly (`MpvVideoSurface.kt:56`) |

And in mpv 0.41 / FFmpeg 8.1 that OwnTV does not use:

| Option | Use |
|---|---|
| `hwdec=mediacodec-copy` | the missing rescue rung ([F09](#f09)) |
| `vo=gpu-next` (libplacebo 7.360.1 is bundled) | better tone-mapping and frame timing on the GL rescue path; A/B against `vo=gpu` |
| `http-header-fields` | per-channel M3U headers ([F16](#f16)) |
| `audio-delay` on live | manual A/V nudge for live ([F19e](#f19--miscellaneous-lower-severity)) |
| `demuxer-lavf-o=fflags=+discardcorrupt` | optional for broken live muxes |

---

## 8. The plan, in phases

Each phase is independently shippable and independently testable on the TV. Phases are ordered by
**(user pain × confidence) ÷ risk**.

### Phase 1 — Make the settings tell the truth (audio) · *fixes issue 1's biggest half*

1. Route **Surround sound** into both ExoPlayer engines:
   `setMaxAudioChannelCount(2)` + `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` when OFF; real
   capabilities when ON. Rebuild the player when the setting flips. [F01]
2. Add the **audio-output watchdog** (`onAudioPositionAdvancing` / `onAudioUnderrun`): if surround was
   chosen and the sink never produces audio (or underruns repeatedly), latch stereo-PCM for the
   session, reload, toast the user. [F02]
3. Widen the mpv surround failsafe: run on **live** too, poll instead of one-shot, add a drift trigger. [F03]
4. Expose the **A/V-sync nudge for live** on mpv. [F19e]
5. Route **Hardware decoding = OFF** into both Exo engines. [F04]

*Risk:* medium (touches audio on every stream). *Mitigation:* Surround OFF is the default, and the new
path for OFF is strictly more conservative than today's.

### Phase 2 — Diagnostics, so provider-specific reports stop being guesswork

6. Runtime toggle for `LiveDiagnosticsLog` in release builds. [F18]
7. HUD **"Report this stream"** → full snapshot into `PlaybackErrorLog` (engine, codec, w×h, declared
   vs measured fps, display mode/Hz, AFR state, dropped frames, buffer, audio format + channels +
   whether passthrough was selected, and the relevant settings) + export/share. [F18]

*Risk:* very low. *Do this early* — every later phase is verified with it.

### Phase 3 — VOD decode ladder · *fixes issue 2*

7b. Add `0xfffffff4` (and the hex errno family) to `PlayerErrors.reasonFor` — one line, and it names
    the real cause of the 4K-movie failures immediately. [F26]
8. Detect a MediaCodec/decoder signature before the MOOV-AT-END branch; stop mislabelling. [F08]
9. Reinstate `hwdec=mediacodec-copy` + `vo=gpu` as an explicit rescue rung, above software and without
   the ≤1080p gate. [F09]
10. Pre-flight `VideoCapabilities` check; on "no hardware decoder for this size", skip straight to the
    rescue rung with an honest message. [F10]
11. `setEnableDecoderFallback(true)` and `forceDisableMediaCodecAsynchronousQueueing()` on the VOD Exo
    engine. [F15]

*Risk:* medium. *Mitigation:* every change is on a failure path that today ends in an error screen.

### Phase 4 — Live buffering control · *fixes issue 3*

12. Drive the ExoPlayer `DefaultLoadControl` from the **Live latency** preset (table in [F06](#f06));
    rebuild on change. [F06]
13. New **"Start after buffering"** setting (Off / 2 / 5 / 10 s) → `bufferForPlaybackMs` on Exo, cache
    pre-roll on mpv; **per-playlist override** stored on `SourceEntity` next to `preferHls`. [F07]

*Risk:* medium — the narrow 8/10 s window exists for a documented reason. Keep today's numbers as
"Balanced" so the default behaviour is byte-identical.

### Phase 5 — One live playback path

14. Extract a single live-tune use-case; make Search, Guide, Favorites and Home call it, so
    Prefer HLS, the Exo→mpv ladder, per-channel pins and quirk learning apply everywhere. [F05]
15. Pass `preferHls` into `XtreamClient.timeshiftUrl` from the Guide catch-up path. [F05]
16. Fix `retry()` to branch on live vs VOD. [F11]
17. Make `reconnectUrlProvider` per-load; install one for Stalker VOD. [F12]
18. Split mpv's 4xx classification into auth / busy / other so **458 and 429 back off and repeat**
    instead of being treated as refusals. [F29]
19. Read Xtream `max_connections` at sync and seed the single-session quirk from it. [F30]

*Risk:* medium-high (touches the routing every screen uses) — hence after the diagnostics phase.

### Phase 6 — M3U parity

20. Parse `#EXTVLCOPT` / `#EXTHTTP` / `#KODIPROP` / pipe-suffix headers into a `headers` column; feed
    mpv `http-header-fields` and Media3 `setDefaultRequestProperties`. [F16]
21. Persist `catchupType`; make `append` work; add `{lutc}` and the flussonic/shift/xc styles. [F17]

*Risk:* low-medium (one Room migration).

### Phase 7 — Frame pacing and polish

22. AFR prompt when a 24/25/50 fps stream meets a 60 Hz output with AFR off. [F13]
23. Decouple fps measurement from the "Measured stream stats" toggle when AFR is on. [F14]
24. A/B `experimentalSetEnableMediaCodecVideoRendererDurationToProgressUs()` and `vo=gpu-next`.
25. Audio focus + `MediaSession` so OwnTV cooperates with other apps and the TV's transport
    controls/Assistant work. [F27]
26. Streaming-scoped OkHttp connection pool so a zap stops evicting the app's whole pool. [F28]
27. F19a (volume boost on live), F19b (mini-player subtitles), F19c (disable the video track in
    audio-only), F19d (hero preview session guard), F19h (measured decode guard), F31 (single-session
    preview hint).

---

## 9. Verification matrix

Every phase needs the same grid walked on a real TV. Build with
`.\gradlew :app:assembleStandardRelease` — never a debug APK.

| Axis | Values |
|---|---|
| Source type | Xtream (`.ts`), Xtream (Prefer HLS), M3U, M3U with per-channel UA, Stalker |
| Content | Live SD/HD, Live 4K, Live 25 fps, Live 50 fps, radio (audio-only), Movie, Episode, Catch-up, Live rewind, Download |
| Engine | mpv, ExoPlayer, forced-mpv pin, forced-Exo pin, after each fallback |
| Mode | Fullscreen, preview pane, docked mini, audio-only, external player |
| Gates | Surround ON/OFF × AFR ON/OFF × HDR ON/OFF × HW decode ON/OFF × Latency Low/Balanced/Stable/Custom × Measured stats ON/OFF |

Minimum per-phase regression set:

* Zap 20 channels in a row; no black screens, no stuck spinner, no lost audio.
* Play a 4K HDR movie start→finish on both engines; check A/V sync at 0 / 30 / 90 min.
* Tune a 25 fps channel with AFR on and off; confirm the display mode actually changes
  (`adb shell dumpsys display | findstr "mActiveMode"`).
* Surround ON on a stereo-only TV: sound must play (stereo), never silence.
* Surround ON on a real 5.1 receiver: 5.1 must play and stay in sync.
* Single-session panel: preview → fullscreen → back, no 458 lockout.
* Stalker: leave a movie paused for >2 h, resume; leave a live channel on for >2 h.
* Upgrade path: `adb install -r` over the published release, existing catalog intact.

---

## 10. Review of the last commit (f76fb8c)

Reviewed as a design decision, not just as the code it left behind — the question being "was there a
better way?". Diff: 12 files, +1094/−45, three independent live faults.

### What is right, and should not be touched

* **`stopAndAwaitRelease()` (`OwnTVPlayer.kt:2358-2367`) is the correct technique.** Queueing a
  no-op barrier behind the stop on mpv's own single-threaded executor and waiting for it is *exact* —
  when the barrier runs, mpv has returned from the stop. That is strictly better than any tuned delay,
  and it is bounded so a wedged core can't freeze an engine switch. Nothing to improve.
* **Release-before-acquire ordering** on both handoffs is the right shape for the one-hardware-decoder
  and one-provider-session constraints.
* **Making the broken-timestamp workaround learned instead of blanket** is a clear improvement and the
  reasoning in the comment is sound: free-running video timing is unsynced from audio *by definition*,
  so applying it to healthy feeds had to drift them.
* **`noteBrokenTimestamp()` (`:896-903`) applies the workaround live** via `mpvAsync` *and* remembers it
  for the next open — I expected it to only remember (leaving the current stream broken until a
  re-tune) and that concern is refuted by the code.
* **Keying broken-PTS per URL but HLS-redirect/458/segment-refusal per host** is exactly the right
  split: a bad mux is one feed's property, a signing scheme or session cap is the panel's.
* **`BROKEN_PTS_HITS = 20`** with the "one discontinuity is normal, these feeds emit it every frame"
  rationale is a well-chosen threshold.
* **Learning the HLS redirect from what ExoPlayer discovered and handing it to mpv** avoids re-paying
  the discovery cost per engine. Good.

### Where there was a better way

| # | Issue | Better approach |
|---|---|---|
| [F29](#f29) | `HTTP_REFUSAL_RX = "HTTP error 4\d\d"` classes **458 as a hard refusal** — contradicting `isSessionLimit`, which the same commit defines as "not a refusal, we're just second". mpv therefore stops repeating and starts changing the request, when repeating is the correct response. | Three-way split: auth/gone (401/403/404/410) → stop repeating; **busy (408/429/458) → back off and repeat the identical request**, as the Exo side already does; other 4xx → current behaviour. |
| [F30](#f30) | The single-session limit is **learned from a failed tune** and forgotten on app restart. | Xtream returns `max_connections`/`active_cons` in `user_info` on every login. Read it at sync, store it, seed the quirk at startup — known before the first zap, and it survives restarts. |
| [F28](#f28) | `releaseHttpConnections()` evicts the **app-wide** OkHttp pool, though its own comment says "this engine's sockets". Every zap drops keep-alive for EPG, metadata and images too. | A streaming-scoped client via `newBuilder().connectionPool(...)` — same proxy/DNS/UA config, its own sockets. |
| [F31](#f31) | Preview suppression on a one-session panel is **silent**. | One-line hint in the preview pane. |
| [F19g](#f19--miscellaneous-lower-severity) | `demuxerLavfOptionsFor(url, live, trimmedRawTsProbe, hls)` uses **one of its four parameters**; the other three are dead. | Reduce to the parameter it actually branches on, or implement the intended cases. Dead parameters invite a future mis-edit — and this function sits on the live open path. |
| — | `HttpClient.kt:115` lost its indentation in the diff (`.replace` de-dented by 8 spaces) while the credential-masking list was extended. | Cosmetic only; worth straightening next time the file is touched. The added `data|auth|signature|sig|key` masking itself is a good hardening. |

### Net assessment

The commit correctly identified and fixed three real, independent faults, and its two structural
decisions — the release barrier and learned-rather-than-blanket quirks — are the right long-term
shape. The one genuine logic gap is **F29**: the mpv retry ladder classifies the very status code this
commit introduced (458) as the opposite of what the commit itself documents it to mean. That is worth
fixing in the same phase as the rest of the live routing work.

---

## Appendix A — Files read for this audit

**Coverage:** every file in `player/` and `core/player/` was opened — the complete list from the
package is reproduced below and nothing in it is unread.

`player/` — **engine & decision logic (audited in depth):** `OwnTVPlayer.kt`, `LivePreviewEngine.kt`,
`ExoSubtitleEngine.kt`, `HeroPreviewEngine.kt`, `PlaybackEngine.kt`, `MpvVideoSurface.kt`,
`FrameRateController.kt`, `PlayerBudget.kt`, `PlaybackErrorLog.kt`, `LiveDiagnosticsLog.kt`,
`LiveStreamQuirks.kt`, `PlayerDiagnostics.kt`, `FpsSample.kt`, `ThroughputTracker.kt`,
`ExoStreamStats.kt`, `PlayerHud.kt`, `DirectTune.kt`.
`player/` — **presentation only, confirmed to contain no backend logic** (they read the engine's
StateFlows and call `togglePlayPause`/`toggleMute`/zoom): `MiniPlayer.kt`, `MiniPlayerLayout.kt`,
`AudioNowPlayingBar.kt`, `SubtitleOverlay.kt`, `SubtitleShift.kt`, `StreamInfoOverlay.kt`,
`ResolutionLabel.kt`, `VideoZoom.kt`, `PendingStopCredits.kt`.
`core/player/`: `VodEngineStore.kt`, `ForceMpvStore.kt`, `EnginePinKey.kt`, `ExternalPlayerLauncher.kt`.
`core/`: `parser/M3uParser.kt`, `parser/XtreamClient.kt`, `epg/CatchupUrl.kt`,
`stalker/StreamUrlResolver.kt`, `database/entity/ContentEntities.kt`,
`database/entity/ProfileEntities.kt`, `network/HttpClient.kt`.
`features/`: `live/LiveViewModel.kt`, `epg/EpgViewModel.kt`, `movies/MovieViewModel.kt`,
`series/SeriesViewModel.kt`, `search/SearchViewModel.kt`, `downloads/DownloadsViewModel.kt`,
`shell/OwnTVShell.kt`, `shell/components/SettingsScreen.kt`,
`settings/VideoPlayerSettingsScreen.kt`, `settings/data/SettingsRepository.kt`,
`settings/data/LiveLatency.kt`.
Also: `MainActivity.kt` (lifecycle → engine background/foreground), `di/DataModule.kt` (the shared
OkHttp singleton), and repo-wide searches for `AudioFocus` / `MediaSession` /
`ACTION_AUDIO_BECOMING_NOISY` / `max_connections` (all of which returned nothing — see F27 and F30).
Git: the full diff of `f76fb8c` (section 10).
Build: `gradle/libs.versions.toml`, `app/build.gradle.kts`.
External: Google Maven `androidx.media3` group index, Media3 release notes,
Maven Central `dev.jdtech.mpv:libmpv`, `libmpv-android` v1.0.0 release notes.
