# Playback Audit — Progress / Status

## What this file is

This is the **progress tracker** for the playback-backend overhaul. It is not the audit.

- **The audit lives in [`PLAYBACK_AUDIT.md`](PLAYBACK_AUDIT.md).** That file holds the findings
  (F01–F31), the gate matrix for every playback setting, the diagnosis of the three reported issues,
  the source-type notes for Xtream / M3U / Stalker, and the full phase plan. Read it first.
- **This file records only *what has actually been built and tested*, phase by phase.**

## Why this file exists

The work is too large for one session — the hourly usage limit and the owner's available time both
run out well before the plan does. So the work is deliberately split into small, independently
testable phases, and the state of each one is written down here.

That means **a new chat session, with no memory of the previous one, can pick the work up from this
file alone**: read `PLAYBACK_AUDIT.md` for the plan, read this file for where the plan has got to,
and start the first phase that is not marked done.

## How a phase is run

1. The phase is implemented and `:app:assembleStandardRelease` is built (release APK — never debug).
2. The phase's entry below is filled in: what changed, which files, what to look for when testing.
3. **The owner tests on the real TV.** Nobody else can — see the constraint below.
4. When the owner says it is good, the phase is marked **`ok, done, tested`** and the next one starts.
5. If the owner reports a problem, the phase is fixed *before* moving on.

Nothing is committed by the assistant at any point; the owner does all git work himself.

## Standing constraint — no user data can be requested

The three reports (RMK62, BouldozeR, ntas-sys) are the evidence we have. **Those reporters cannot be
asked for logs, screenshots, or test builds** — they are not active and not technically inclined. So
every fix here is derived from the screenshots and reasoning already captured in `PLAYBACK_AUDIT.md`,
not from a new round of user diagnostics. If anyone reports again later, it is fixed then.

This is also why the diagnostics work moved to the **last** phase: better logging cannot help issues
we will never receive a log for. It is worth doing for the future, not for these three.

## Testing reality check — the owner's hardware

Established by looking up the actual devices (2026-08-02):

- **TV: TCL 50C61KS / 50C6KS** — decodes Dolby Atmos, Dolby Digital / Digital Plus and DTS:X/Virtual:X,
  has eARC on one HDMI and an optical out, but its own speakers are an **Onkyo 2.1, 2×15 W** array.
- **Speakers: Speedlink SL-830100-BK Gravity Carbon RGB** — a **2.1 stereo** system, 3.5 mm analogue
  or Bluetooth. Both of those transports are stereo-only; there is no digital multichannel path in.

So **real 5.1/7.1 output cannot be verified on this setup.** The TV *reports* surround support to
Android (it genuinely has the decoders), accepts a compressed multichannel bitstream, and downmixes
it to 2.1 — which is precisely the path that adds latency and, on some streams, fails.

What the owner **can** test: whether audio starts, stays in sync, and whether the fallback to stereo
fires and recovers. What the owner **cannot** test: that six discrete channels reach six drivers.
Phase 1 therefore surfaces the choice as text (see the new *Audio out* row) instead of asking anyone
to judge it by ear.

---

# Phase status

| # | Phase | Status |
|---|-------|--------|
| 1 | Surround 3-state + audio watchdog + HW-decode reaches Exo + audio readout | **ok, done, tested** |
| 2 | VOD decode ladder (BouldozeR) | **ok, done, tested** |
| 3 | Live latency drives the Exo LoadControl + pre-roll gate (ntas-sys) — DB 25 | **ok, done, applied-confirmed** |
| 4 | AFR: 25fps@60Hz prompt, fps measurement decoupled from the stats toggle, "Pre-buffer" rename | **ok, done, tested** |
| 5 | One shared live path (Prefer-HLS everywhere), VOD Retry, Stalker VOD reconnect, 458 | **ok, done, tested** |
| 6 | M3U per-channel headers + working catch-up `append` — DB 26 | **ok, done, tested** |
| 7 | Diagnostics, MediaSession, audio focus (duck-don't-pause), connection-pool scoping | **ok, done, tested** |

---

## Phase 1 — Audio output: make the setting real, and make failure recoverable

**Status: `ok, done, tested`** — owner-tested on the TCL 50C61KS, 2026-08-02.

Tested in **Auto** mode across **Live TV and Series, on both engines (mpv and ExoPlayer)** — all
working, no regressions, no false fallback observed.

Built with `:app:assembleStandardRelease`; APK verified signed with the real release key
(`CN=Ashiq Hasan`, SHA-256 `e8fbd2f2…`), so it installs over an existing v4.1.6 with `adb install -r`
and keeps all data. **No Room migration in this phase — the database is untouched (still v24).**

### What this phase fixes, and why

The old "Surround sound" switch had two problems that the audit (F01–F04) pinned down:

1. **It only ever reached mpv.** Live TV's default engine is ExoPlayer, so on Live TV the switch did
   nothing at all in either position. A TV that mis-plays Dolby got handed Dolby whatever the user set.
2. **There was no fallback on ExoPlayer.** Media3 passes AC-3/E-AC-3/DTS through whenever the sink
   *claims* to support it — no verification, no recovery. That is the RMK62 "picture but no sound" and
   "audio drifts" report, and it is why turning surround off did not help him: off changed nothing.

### Changes

**New: `app/src/main/java/tv/own/owntv/player/AudioOutputPolicy.kt`**
The shared audio policy every engine now reads.
- `SurroundMode` — `AUTO` / `STEREO` / `SURROUND`, with the migration rule from the old boolean.
- `AudioOutputPolicy` — the **session-wide stereo latch**. Deliberately global: the fault lives in the
  device's audio HAL or the HDMI sink, not in a stream, so a lesson learned by one engine must apply
  to all of them immediately — otherwise you zap to the next channel, land on the other engine, and
  lose sound again. In-memory only; a latch is a statement about *this output right now*, and
  persisting it would permanently downgrade a genuinely capable receiver.
- `OwnTVRenderersFactory` — a `DefaultRenderersFactory` that can pin the audio sink to
  `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` (16-bit stereo PCM, **no passthrough**). Capping the
  *sink* is what actually removes the bitstream path, because `MediaCodecAudioRenderer` asks the sink
  what it supports before choosing; a track-selection constraint alone would not.
- `AudioWatchdog` — an `AnalyticsListener` + a `poll()` the owning engine calls from the health tick it
  already runs. It owns no timer, so it cannot outlive an engine. Three detections: a sink error;
  **armed but never advancing** (an audio format was accepted and handed to a decoder, but the
  AudioTrack playback head never moved within 6 s of *playing* time — the exact "picture, no sound"
  signature); and 4 underruns inside 10 s.

**Settings — the switch becomes three states**
`SettingsRepository.surroundMode` (new `surround_mode` string key), `SettingsViewModel.cycleSurroundMode()`,
and the row in `SettingsScreen.kt`. Migration is read-time, so **nothing is rewritten on upgrade**:
never touched the old switch → **Auto**; explicitly on → **Surround**; explicitly off → **Stereo only**.
The legacy boolean is kept in sync so a downgrade to 4.1.6 lands somewhere sane. `surround_mode` was
added to the settings-backup whitelist.

**The watchdog runs in all three modes and cannot be turned off**, including Surround — a user who
asked for 5.1 did not ask for silence.

**`LivePreviewEngine.kt`** (Live TV's default engine)
- Reads `surroundMode`; builds through `OwnTVRenderersFactory`.
- Registers `AudioWatchdog` and polls it from `progressWatchdog` (the 2.5 s tick already running).
- New `rebuildForAudioChange()` — a **rebuild**, not a reload: a sink's capabilities are fixed at
  construction, so re-preparing the same player would keep the sink that just failed.
- On a hit: latch → notify via `onAudioFallback` → rebuild on a stereo-only sink.

**`ExoSubtitleEngine.kt`** (VOD / the mpv fallback engine)
- Same policy, watchdog polled from the existing ~0.5 s position tick.
- The cached-player rebuild check now covers the audio sink as well as the decoder path
  (`builtForStereo`), so a player built before the latch tripped is dropped rather than reused.

**`OwnTVPlayer.kt`** (mpv)
- Now reads `surroundMode` instead of the boolean, and shares the global latch, so mpv's own
  runaway detector protects the ExoPlayer engines too — and vice versa.
- The surround failsafe **now runs on Live TV, not just VOD** (it was VOD-only), and gained a second
  detection alongside the existing 2× runaway check: **silence** — `audio-pts` frozen while `time-pos`
  advances, sampled over a 4 s window. The runaway check cannot see this, and it is the live failure
  mode that was reported.

**Hardware decoding now reaches ExoPlayer (F04)**
The setting used to reach mpv only, so a user who turned it off to dodge a broken vendor decoder still
got that decoder the moment playback landed on ExoPlayer. Both Exo engines now put software decoders
first when it is off, with hardware still in the list as a backstop (Media3 walks the list in order and
falls through on failure), so this can only *add* a route, never remove one.

**Audio readout in stream info (new "Audio out" row, all three engines)**
Shows whether the audio is **passthrough (TV/receiver decodes)** or **decoded in app**, whether
multichannel is currently allowed, and — if the safety net has fired — the reason. This is the only way
to verify an audio setup without a receiver that displays its own input format, which is exactly the
owner's situation. The live engine's "Decoder" row now reports the *real* decoder name instead of a
hard-coded "(hardware)", which would have been actively misleading once HW decoding can be off.

### Files changed

| File | Why |
|---|---|
| `player/AudioOutputPolicy.kt` | **new** — SurroundMode, session latch, stereo-pinned renderers factory, AudioWatchdog |
| `player/LivePreviewEngine.kt` | 3-state mode, watchdog + poll, rebuild-on-audio-change, HW-decode selector, Audio out row, real decoder name |
| `player/ExoSubtitleEngine.kt` | 3-state mode, watchdog + poll, sink-aware rebuild check, HW-decode flag, Audio out row |
| `player/OwnTVPlayer.kt` | 3-state mode, shared latch, failsafe extended to live + silence detection, pushes mode/HW flag into the Exo engine, Audio out row |
| `features/settings/data/SettingsRepository.kt` | `surroundMode` flow + setter, `surround_mode` key, backup whitelist |
| `features/settings/SettingsViewModel.kt` | `surroundMode` state, `cycleSurroundMode()` (clears the latch) |
| `features/shell/components/SettingsScreen.kt` | 3-state row + per-state description, search entry |

### What the owner should test

Install over the existing app (**keeps all data**):

```powershell
adb install -r app\build\outputs\apk\standard\release\app-standard-release.apk
```

1. **Settings → Playback → Surround sound** cycles Auto → Stereo only → Surround → Auto, and the
   description text changes with it. Since the old switch was never explicitly turned on, it should
   read **Auto** on first launch.
2. **Live TV, all three modes.** Sound present, in sync, and zapping still feels the same. Changing
   the setting while a channel is playing should re-open that channel (a brief reload) and keep sound.
3. **Movies and Series, all three modes** — on both engines. Force the ExoPlayer VOD path by playing
   something with an image-based (PGS/VOBSUB) subtitle.
4. **The new readout.** Stream info now has an **Audio out** row. On the TCL it should most often say
   *passthrough (TV/receiver decodes)* in Auto/Surround, and *decoded in app · stereo only* in
   Stereo only mode. This is the row to screenshot if anything looks wrong.
5. **Hardware decoding OFF** (Settings → Video player). Live TV and VOD should still play — slower and
   possibly capped in resolution, which is expected — and the **Decoder** row should now name a
   software decoder (e.g. `OMX.google.…` / `c2.android.…`) instead of claiming hardware.
6. **The fallback, if it ever fires:** a toast saying the TV couldn't play the audio and it switched to
   stereo, sound returning within a few seconds, and the *Audio out* row showing `fell back: …`.

### Design question raised and settled: should there be an "Off" option?

Asked during testing: alongside Auto / Stereo only / Surround, should there be a fourth "Off"?

**Decided no.** "Stereo only" *is* the old switch's off position — renamed to describe what it actually
does rather than what it disables. A separate "Off" would either duplicate it, or mean "apply no policy
at all", which is exactly the pre-4.1.7 behaviour this phase removed: hand the sink whatever it claims
to support and never verify. That is the state that produced RMK62's silent 4K channels.

The three options are the three real answers to *who decodes Dolby/DTS* — the TV (Surround), OwnTV
(Stereo only), or decide-and-self-correct (Auto). There is no fourth answer. The old label was the
problem: "Surround: Off" read as *turning a feature off* when it meant *choosing stereo*, so users left
it on expecting better sound and got desync.

**Small follow-up agreed instead (not yet done):** add `off` / `disable` to the settings-search keywords
for that row, so a user hunting for "off" still finds "Stereo only". One line in `SettingsScreen.kt`,
no behaviour change. Do this at the start of the next session.

### Known limits / risks of this phase

- **False positives are the main risk.** The silence detector requires an audio track to be selected,
  the video clock to have advanced, and the audio clock to be frozen — but a stream that legitimately
  carries several seconds of digital silence at the start of a load could in principle trip it. The
  cost of a false positive is a one-time reload into stereo, not a failure, but it is the thing to
  watch for and report.
- The stereo-only sink is built via a **deprecated** Media3 builder — deprecated precisely because
  passing a `Context` makes the sink query real device capabilities and ignore the cap, which is the
  one thing we must prevent. It is wrapped so that if a future Media3 removes it we lose the guarantee
  rather than the playback.
- **Not in this phase:** the live A/V-sync nudge and the 25 fps judder work. Those belong with AFR and
  are Phase 4 — this phase covers the *audio* half of RMK62's report, not the judder half.
- `PlaybackErrorLog` still records only hard errors, so a fallback event will not appear in Settings →
  Playback error log yet. That is Phase 7.
- **Not exercised by the owner's test run** (and not exercisable on his hardware, see the hardware note
  above): the Surround and Stereo-only modes were not each walked through separately, and the fallback
  path has never actually fired on real hardware — no stream available to him triggers it. The logic is
  reasoned from the reports, not observed. If a user reports silence after this ships, that is the first
  thing to check.

### Follow-up from Phase 1 — done

The `off` / `disable` search keywords were added to the surround row in `SettingsScreen.kt`
(2026-08-03), so searching Settings for "off" still finds "Stereo only". No behaviour change.

---

## Phase 2 — VOD decode ladder (BouldozeR)

**Status: `ok, done, tested`** — owner-confirmed on the TV. **No Room migration in this phase — the
database is untouched (still v24).**

Covers `PLAYBACK_AUDIT.md` F26, F08, F09, F10.

### What this phase fixes, and why

BouldozeR's 4K movies fail on the Hisense while external mpv plays them. Four separate defects stack up
into that one report:

1. **F26 — the error code wasn't in the humanization table.** MediaCodec logs errnos as unsigned hex, so
   `err 0xfffffff4` (−12, `NO_MEMORY`) never matched the decimal-only ENOMEM regex. `reasonFor()` came
   back `null`, which is exactly why the wrong "re-encode your file" text had nothing competing with it.
2. **F08 — the MOOV-AT-END watchdog mislabelled decoder failures and dead-ended them.** "Loaded, but no
   height and no bitrate" is equally the signature of a video decoder that failed to start. The user was
   told their provider's file is malformed, with the MediaCodec error sitting on the same screen
   contradicting it — and there was no retry, no software attempt, no ExoPlayer fallback after it.
3. **F09 — there was no rung between "direct hardware" and "software ≤1080p".** The software rescue is
   gated at 1080p (above that `enforceDecodeGuard()` aborts, correctly — TV CPUs can't sustain it), so a
   4K file the direct path can't open had **no rescue at all**.
4. **F10 — nothing ever asked the device what it can decode.** Many TV SoCs advertise 4K for HEVC/VP9/AV1
   only and cap **AVC at 1920×1080**. A 3840×1608 H.264 file is therefore not hardware-decodable there —
   a queryable fact that was surfacing as a wrong error message.

### Changes

**`player/PlayerDiagnostics.kt` (F26)**
`ENOMEM_RX` now accepts the hex spelling (`err 0xfffffff4`) alongside `-12`, and `0xfffffff4` anywhere in
the text maps to the ENOMEM reason. That alone turns the reported error screen from *"ask your provider
to re-encode"* into *"Device ran out of memory for the decoder — try closing other apps"*.

**`player/OwnTVPlayer.kt` — the copy rescue rung (F09)**
`hwdec=mediacodec-copy` + `vo=gpu` is back, but **only as a rescue**, never as a default and never for a
stream that plays. New per-item flags `forceCopyThisLoad` / `triedCopyRescue`, folded into `targetHwdec()`
/ `targetVo()` and cleared on every genuinely-new item. It is still *hardware* decoding — the SoC does the
decode, GL only composites — so it carries **no ≤1080p gate**, and `enforceDecodeGuard()` leaves it alone
(`hwdec-current` reads `mediacodec-copy`, not `no`). This is almost certainly why external mpv plays these
files: mpv-android defaults to `mediacodec-copy`.

**`player/OwnTVPlayer.kt` — the ladder (F08/F09)**
New `tryDecodeRescue()` steps down one rung and reloads the same item: **copy rescue → software → give up**
(after which VOD still gets the existing ExoPlayer fallback). It is wired into three places:
- the MOOV-AT-END watchdog, which now **classifies first**: a decoder signature in `lastMpvError` /
  `diagnostics.recentError()`, or a provable hardware-capability miss, routes to the ladder instead of
  blaming the file; only a genuine container fault still gets the re-encode message;
- the decode-check "direct never engaged" branch, between the retries and the software rung;
- the END\_FILE "playback didn't start" branch, ahead of the ≤1080p software rung, when the captured error
  looks like a decoder failure.

**`player/OwnTVPlayer.kt` — pre-flight capability check (F10)**
`hardwareCanDecode(mime, w, h)` queries `MediaCodecList` / `VideoCapabilities.isSizeSupported()` across the
hardware decoders only (`isHardwareAccelerated` on API 29+, Google-prefix heuristic below it), plus
`videoMimeFor()` to map mpv's codec names. It returns `null` for "unknown" and **only a definite `false` is
ever acted on**. Two uses: skip the pointless direct retries when the codec list already says no hardware
decoder covers this size (with a toast: *"This TV can't decode this video in hardware — trying another
way"*), and give an honest terminal message — *"This TV's video hardware can't decode 4K H264. The file is
fine"* — instead of blaming the provider.

### Files changed

| File | Why |
|---|---|
| `player/PlayerDiagnostics.kt` | hex errno `0xfffffff4` → ENOMEM reason (F26) |
| `player/OwnTVPlayer.kt` | copy rescue rung + flags, `tryDecodeRescue()`, MOOV branch classification, hardware capability pre-check, honest decode-failure message (F08/F09/F10) |
| `features/shell/components/SettingsScreen.kt` | Phase 1 follow-up: `off` / `disable` search keywords |

### What the owner should test

Install over the existing app (**keeps all data**):

```powershell
adb install -r app\build\outputs\apk\standard\release\app-standard-release.apk
```

1. **No regression is the main thing.** Movies, Series, catch-up and Live TV should all start exactly as
   before, on the direct path, with `Direct` still shown in stream info. The copy rung must never appear
   for content that already plays — if a normally-fine 4K movie starts looking like a slideshow, the rung
   is firing when it shouldn't, and that is the bug to report.
2. **A file that used to fail.** If any stream previously showed *"This video isn't formatted for
   streaming…"*, it should now either start playing (via copy or software) or show an error that names the
   decoder/TV rather than the file.
3. **Hardware decoding OFF** (Settings → Video Player) still behaves as it did — the copy rung is skipped
   entirely in that mode.
4. **Stream info** during a rescue: `Decoder` should read `mediacodec-copy` and the render path is no
   longer Direct (the app draws subtitles itself there).

### Known limits / risks of this phase

- **The rescue paths cannot be triggered on the owner's hardware** — the TCL decodes everything he has.
  As with Phase 1, the logic is reasoned from the report, so the realistic test is "nothing regressed".
- `mediacodec-copy` on 4K HDR is genuinely slow; that is accepted, because the alternative on the affected
  TVs is an error screen. It is gated to one attempt per item.
- `looksLikeDecoderFailure()` is a text match over mpv/logcat output. A false positive costs one extra
  reload down the ladder, not a failure — but it is what to watch for.
- The capability check reads the stream's *declared* size, so it can only help once mpv has reported
  width/height. When it hasn't, the check returns "unknown" and the old behaviour applies unchanged.

### Owner test result

Owner confirmed Phase 2 works — no regression on his catalog.

## Phase 3 — Live latency actually drives buffering (ntas-sys)

**Status: `ok, done, tested`** — owner-confirmed, including the applied-setting readout. F06 / F07.
Room **DB 24 → 25**.

### What this phase fixes, and why

Two findings, one cause: the Live latency setting was mostly decoration on the ExoPlayer side.

- **F06 — the setting didn't reach the Exo buffer.** `LivePreviewEngine` built its `DefaultLoadControl`
  from hard-coded constants (8000 / 10000 / 1000 / 2000 ms) no matter what Live latency was set to. The
  only thing the setting fed was `MediaItem.LiveConfiguration.targetOffsetMs`, which HLS and DASH honour
  and a raw MPEG-TS stream ignores completely — which is exactly what ntas-sys's provider serves. So
  "High latency / most stable" changed nothing for him.
- **F07 — nothing to buffer *before* starting.** Exo started at 1 s of data and mpv started as soon as it
  had a frame. On a provider that delivers in bursts, that means playback begins on an almost-empty
  buffer and stalls a few seconds in, every time. There was no way to say "fill up first, then play".

The fix makes Live latency drive the actual buffer depth on both engines, and adds a separate
**Pre-buffer** control (off / 2 / 5 / 10 s) — a pre-roll gate — plus a **per-playlist
override** of it, because a user typically has one bad provider and several fine ones.

Balanced + Pre-buffer Off reproduces the previous numbers **exactly** (8000/10000/1000/2000),
so anyone who doesn't touch the new setting sees no behaviour change at all.

### Changes

- **`LiveBuffer.loadControlFor(bufferSecs, prerollSecs)`** is the new single source of the math, shared by
  both engines: buffer depth comes from Live latency, the start/restart thresholds come from the pre-roll
  (or the old 1 s/2 s defaults when it's off), and `minBufferMs` is clamped up so it can never fall below
  the start thresholds — `DefaultLoadControl` asserts on that. `targetBufferBytes()` scales the byte cap
  along with the depth above Balanced, capped at 3×, so a long buffer isn't silently truncated.
- **ExoPlayer** (`LivePreviewEngine`) now feeds that into `setBufferDurationsMs` / `setTargetBufferBytes`,
  and rebuilds the player when either Live latency or the pre-roll changes (previously only the audio
  setting rebuilt it — `rebuildForAudioChange` is now `rebuildForSettingChange`). The resolved numbers are
  written to the live diagnostics log as a `load_control` line.
- **mpv** (`OwnTVPlayer`) gets the equivalent gate for live content: `cache-pause-initial=yes` with
  `cache-pause-wait=<pre-roll>`, and `demuxer-readahead-secs` is raised to at least the pre-roll so the
  gate can actually be satisfied. With the pre-roll off, both properties return to the old values.
- **Per playlist**: `sources.livePrerollSecs` (`-1` = follow global, `0` = off, `N` = seconds), set from
  Settings → Video Player → Live TV → *Pre-buffer per playlist*. Every live play site
  (`LiveViewModel` ×5, `EpgViewModel`, `SearchViewModel`) passes the source's override down to the engine.
- **Room 24 → 25**: guarded `ALTER TABLE sources ADD COLUMN livePrerollSecs INTEGER NOT NULL DEFAULT -1`
  plus the standing `healSchema(db)` on the final hop. Migration registered in `DatabaseModule`, schema
  `25.json` exported, migration test updated to v25.

### Files changed

| File | Why |
|---|---|
| `features/settings/data/LiveLatency.kt` | new `loadControlFor()` / `targetBufferBytes()` / pre-roll choices — the shared math |
| `player/LivePreviewEngine.kt` | LoadControl from the setting, rebuild on latency/pre-roll change, per-play override (F06/F07) |
| `player/OwnTVPlayer.kt` | mpv pre-roll gate via `cache-pause-initial` / `cache-pause-wait`, readahead floor, `play(..., livePrerollSecsOverride)` |
| `core/database/entity/ProfileEntities.kt` | `SourceEntity.livePrerollSecs` + `FOLLOW_GLOBAL_PREROLL` |
| `core/database/OwnTVDatabase.kt` | version 25, `MIGRATION_24_25` |
| `di/DatabaseModule.kt` | register `MIGRATION_24_25` |
| `core/database/dao/SourceDao.kt` | `updateLivePreroll()` |
| `features/settings/data/SettingsRepository.kt` | `livePrerollSecs` flow + backup key |
| `features/settings/SettingsViewModel.kt` | global + per-source pre-roll setters |
| `features/settings/VideoPlayerSettingsScreen.kt` | two new Live TV rows and their picker dialogs |
| `features/shell/components/SettingsScreen.kt` | settings-search entry for the new row |
| `features/live/LiveViewModel.kt`, `features/epg/EpgViewModel.kt`, `features/search/SearchViewModel.kt` | pass the per-playlist override at every live play site |
| `androidTest/.../OwnTVDatabaseMigrationTest.kt` | `CURRENT_VERSION = 25`, new hop in the chain, column assertion |
| `app/schemas/.../25.json` | exported schema |

### What the owner should test

Install over the existing app (**keeps all data** — this is also the DB 25 upgrade test):

```powershell
adb install -r app\build\outputs\apk\standard\release\app-standard-release.apk
```

1. **The upgrade itself.** First launch after installing must not crash and the catalog, playlists,
   favourites and history must all still be there. That is the migration.
2. **No regression at defaults.** With Live latency on Balanced and *Pre-buffer* off, live
   channels should zap and play exactly as they do today.
3. **Live latency now does something.** Set it to the highest option and zap a few channels: expect a
   noticeably longer wait before the picture, and steadier playback after. Lowest option should feel
   snappier to tune. Previously both felt identical on MPEG-TS.
4. **Pre-buffer.** Set it to 5 s and open a live channel. On a fast provider the picture still appears
   almost at once — that is correct, five *seconds of video* arrive in a fraction of a second. What must
   be true is that Stream info shows `pre-buffer 5s of video`; on a provider that trickles, the same
   setting produces a visible wait. Works on both engines, so worth trying with ExoPlayer preferred and
   with mpv.
5. **Per playlist.** With more than one playlist, set *Pre-buffer per playlist* for just one of
   them and confirm only that playlist waits; the others follow the global setting.
6. **D-pad.** The two new rows and their dialogs behave like the rest of the Video Player screen, and
   focus returns to the right row after closing a dialog.

### Known limits / risks of this phase

- **This is a real behaviour change on the Exo live path**, unlike Phases 1–2. Balanced + Off is
  byte-for-byte the old configuration, but any other combination genuinely alters buffering — so item 2
  above is the important one.
- A large latency setting combined with a large pre-roll means a long black wait before the first frame.
  That is the intended trade, but it will feel slow if someone maxes both.
- The mpv gate uses `cache-pause-wait`, which is a *demuxer cache* measure, not a decoded-frames measure —
  the wall-clock delay may not land exactly on the chosen number of seconds.
- `targetOffsetMs` still only affects HLS/DASH. Nothing regressed there, but the setting's effect on a
  proper HLS provider now comes from two mechanisms at once.
- The migration test was **compiled only, never executed** — running instrumentation tests reinstalls the
  app and would wipe the owner's data. It can be run on an emulator if wanted.

### Fix after the first Phase 3 test (owner: "10s pre-roll, still starts within 1 s")

Real bug on the ExoPlayer live path, found from the report. `LivePreviewEngine` keeps **one** ExoPlayer
alive across tunes (`player ?: build()`), and a `LoadControl` is fixed when the player is constructed. The
rebuild-on-change hook only fired when something was already playing (`currentUrl != null`) — so changing
*Pre-buffer* from the Settings screen, with nothing playing, left the old player in place and
the next channel used the old 1 s threshold. A per-playlist override differing from the previous channel's
had the same hole.

`play()` now compares the wanted `LoadControlMs` against the one the live player was actually built with
(`builtLoadControl`) and releases/rebuilds when they differ; the rebuild is written to the live diagnostics
log. Also: a live-latency change applied to a *playing* channel on mpv now keeps the pre-roll floor on
`demuxer-readahead-secs`, and mpv logs a `live_buffer preroll=…` line per live open (tag `OwnTVPlayer`).

**Confirmed on the owner's TV** (2026-08-03), Live TV on ExoPlayer, pre-roll set to 10 s:
`live_buffer preroll=10s start=10000ms min=10000ms max=12000ms latency=-1` on every tune. The setting
reaches the engine; the buffer simply fills faster than a second on his line. (`latency=-1` = Balanced,
i.e. unset, and the depth is clamped up to the 10 s pre-roll as designed.)

**Second report ("still starts fast with 10 s"): most likely not a bug — the unit is seconds of *video*,
not seconds of *waiting*.** The gate holds the picture until N seconds of media are buffered. On the
owner's connection a provider ships 10 s of a live stream in well under a second of wall clock, so the gate
opens immediately and playback looks unchanged. That is the correct outcome: for ntas-sys, whose provider
trickles, the same setting produces a real wait. To make the difference checkable rather than a matter of
faith, both engines now expose the resolved numbers **on screen** — a `Live buffer` row in the Stream info
panel (`pre-buffer 10s of video · depth 10s`, plus `playlist override` when one applies). On mpv the values
are read back out of mpv itself (`cache-pause-initial` / `cache-pause-wait` / `demuxer-readahead-secs`), so
the row proves the properties actually landed. They also go to Logcat unconditionally (`live_buffer`), but
note the `OwnTV-LivePreviewEngine` diagnostics tag is silent in a plain release build — the full live trace
needs `./gradlew :app:assembleStandardRelease -PdiagnosticBuild=true`.
If a *wall-clock* delay is ever wanted, that is a different feature and would not cure stutter.

### Naming fix after the second Phase 3 test — "Start after buffering" → "Pre-buffer"

The owner hit the misconception himself even knowing how it works: the setting read **"Start after
buffering"** and the Stream info row read **"start after 10s"**, so both looked like a promise to wait ten
seconds, and a correctly-working setting looked broken. Every user would have asked the same question. The
wording now names the *amount of video* everywhere instead of a moment in time:

| Where | Was | Now |
|---|---|---|
| Settings row | Start after buffering | **Pre-buffer live streams** |
| Per-playlist row | Start after buffering per playlist | **Pre-buffer per playlist** |
| Picker options | `Off` / `2s` / `5s` / `10s` | `Off` / **`2s of video`** / `5s of video` / `10s of video` |
| Stream info (both engines) | `start after 10s · depth 10s` | **`pre-buffer 10s of video · depth 10s`** |
| Off, in Stream info | `start now` | **`pre-buffer off`** |

The compact chip on the settings row stays `10s` (no room), which is why the *picker* carries the unit —
the unit is visible exactly where the value is chosen. The description was rewritten around the same idea:
"It is an amount of video, not a countdown: a fast provider delivers 10s of video in well under a second,
so the channel still starts instantly and that is correct." `LiveBuffer.prerollValueLabel()` is the single
source of the long form. Settings search still matches the old words ("start after buffering", "preroll"),
so anyone who read the old name can still find it. **No behaviour, keys, DB columns or defaults changed —
this is naming only.**

**VOD starting immediately is correct, not a bug** — the pre-roll gate is deliberately live-only (`isLive`
content on mpv, the live engine on Exo). VOD arrives from a seekable server at full speed; making a movie
wait ten seconds before it starts would be a regression, not a feature. If a pre-roll for VOD is wanted, it
should be its own setting.

### Next session — start here

Owner test of Phase 3 first (especially the DB 25 upgrade and the "no regression at defaults" check). When
confirmed, begin **Phase 4** — AFR and the 25 fps judder, F13/F14, the video half of RMK62's report.

## Phase 4 — AFR and the 25 fps judder (RMK62)

**Status: `ok, done, tested`** — owner-confirmed on the TV ("all checked all ok"), including the
"Pre-buffer" naming fix he asked for after the test (recorded in the Phase 3 section). **No Room migration
in this phase — the database stays at v25.**

### What this phase fixes, and why

RMK62's report is "25 fps live channels judder". On mpv's direct path (`vo=mediacodec_embed`) the app does
not control scan-out: decoded frames go straight to the decoder's surface and the panel presents them on
its own cadence. 25 fps on a fixed 60 Hz output therefore becomes an uneven 2:3 cadence, and **Auto frame
rate is the only cure** — nothing in the player can smooth it over. Two things stopped that cure from
working:

- **F14 — the frame rate was often unknown, so AFR had nothing to act on.**
  - On mpv, `_videoFps` was fed *only* by `container-fps`. Live MPEG-TS usually doesn't declare one, so the
    fps stayed `null`, `AutoFrameRateEffect` received `null`, and AFR did **nothing at all on mpv live** —
    exactly the content it exists for.
  - On ExoPlayer the fps measurement was gated behind the *Measured stream stats* toggle, so with stats off
    (the default) an unlabelled stream again gave AFR nothing.
- **F13 — AFR ships off by default and is unadvertised.** A user watching juddering 25 fps content has no
  way to learn the setting exists, and the judder does not look like a setting problem.

### Changes

1. **mpv: measure the frame rate when the stream doesn't declare one** (`OwnTVPlayer.kt`). Once playback
   has settled (`LIVE_FPS_PROBE_MS` = 6 s) and only if `container-fps` never arrived, `estimated-vf-fps` is
   sampled **twice, a second apart**, and published only when the two agree within 0.5 fps *and* land within
   0.6 fps of a broadcast rate (`STANDARD_FPS`: 23.976/24/25/29.97/30/50/59.94/60). Skipped while paused or
   seeking. Two agreeing samples matter because the start-up burst and cache stalls skew a single reading,
   and a wrong number here would ask the TV for the *wrong* display mode. Logged as
   `measured fps: est-vf-fps=… -> 25.0fps (no container-fps)`.
2. **ExoPlayer: run the fps measurement whenever AFR is on** (`LivePreviewEngine.kt`), not only when
   *Measured stream stats* is on — the measurement is cheap (≤5 samples) and AFR depends on it. Turning AFR
   on mid-channel now also kicks a measurement immediately instead of waiting for the next tune.
3. **F13 one-time suggestion** (`AutoFrameRatePrompt.kt`, new). Deliberately conservative: full-screen only,
   AFR off, the display genuinely on a rate that is not a multiple of the stream's, **and** a matching mode
   existing at the current resolution (`FrameRateController.betterRefreshRateFor()` — on a 60 Hz-only panel
   there is nothing to offer, so nothing is said), after 8 s of continuous playback at that rate, and shown
   **once ever** whichever way it is answered. It names the actual numbers ("25 fps … 60 Hz … switches to
   50 Hz"), warns that some TVs blank briefly on a rate change, and says where to turn it off again.
4. **mpv `video-sync` on the non-direct path.** `video-sync` is now `desync` for broken-PTS streams (as
   before), `display-resample` when the direct path is *not* in use, and `audio` otherwise. `display-resample`
   only means anything on `vo=gpu`, i.e. the software/GL rescue ladder — on the direct path it is inert, so
   this cannot regress normal playback.

### Files changed

- `app/src/main/java/tv/own/owntv/player/OwnTVPlayer.kt` — measured-fps fallback (`readVfFps()`,
  `LIVE_FPS_PROBE_MS`, `STANDARD_FPS`), `video-sync` selection.
- `app/src/main/java/tv/own/owntv/player/LivePreviewEngine.kt` — fps measurement no longer gated on the
  stats toggle when AFR is on; kick on `setAutoFrameRateEnabled(true)`.
- `app/src/main/java/tv/own/owntv/player/FrameRateController.kt` — `betterRefreshRateFor()`,
  `currentRefreshRate()` (read-only helpers; the switching logic is untouched).
- `app/src/main/java/tv/own/owntv/player/AutoFrameRatePrompt.kt` — **new**, the F13 dialog.
- `app/src/main/java/tv/own/owntv/features/settings/data/SettingsRepository.kt` — `autoFrameRatePrompted`
  flag (also set when AFR is enabled from Settings, so the prompt never appears to an existing AFR user),
  included in backup keys.
- `app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt` — mounts the prompt, sourcing fps from the
  live preview engine or the mpv player depending on which is active.

### What the owner should test

1. **A 25 fps live channel with AFR off, full screen.** After ~8 s the suggestion should appear **once**.
   Answer "Not now" — it must never come back, including after a restart. (To see it a second time during
   testing, the flag has to be cleared with app data, so decide before answering.)
2. **"Turn on" from the prompt** — AFR should engage immediately on the channel that is playing, and
   Settings → Video Player should show it on.
3. **On a 60 Hz-only panel, the prompt must never appear** — there is no better mode, so there is nothing
   honest to offer.
4. **mpv live fps** — with a compatibility-pinned live channel, Stream info should now show a frame rate
   where it previously showed none, and `adb logcat -s OwnTVPlayer:I` should carry a `measured fps:` line.
5. **No regression with AFR already on** — zapping should behave as before; the prompt must stay silent.
6. **Software decode path**: turn Hardware decoding *off* on a movie (this is the only way to exercise the
   `video-sync=display-resample` change) and check that playback still starts and does not stutter worse.

### Known limits / risks of this phase

- The measured fps is a *guess with guard rails*. If a stream really runs at a non-standard rate, no fps is
  published — deliberately, because AFR acting on a wrong number is worse than AFR not acting.
- The prompt is once-ever per install. If it fires on a badly-chosen channel the user has spent their one
  chance; the setting remains discoverable in Settings → Video Player.
- AFR itself is unchanged: on panels whose manufacturer ignores `preferredDisplayModeId`, turning it on
  still does nothing. This phase only makes sure it *has the data* and is *discoverable*.
- `video-sync=display-resample` is inert on the direct path, so item 6 above is the only place the change
  can be observed at all.

### Owner test result

Owner tested on the TV: **all checked, all ok.** He then flagged the pre-roll wording as misleading
("start after 10s" read as a ten-second countdown) — fixed as a naming-only change, documented in the
Phase 3 section, and also confirmed by him.

## Phase 5 — One shared live path (F05 / F11 / F12 / F29)

**Status: `ok, done, tested`** — owner-confirmed on the TV ("yes all working"). `:app:assembleDebug`,
the full unit-test suite and `:app:assembleStandardRelease` all clean, APK verified signed with the real
release key (`CN=Ashiq Hasan`). **No Room migration in this phase — the database stays at v25.**

### What this phase fixes, and why

Live TV grew several *parallel* ways to start a channel. Only the one in `LiveViewModel` had all the
knowledge — Prefer HLS, the ExoPlayer→mpv ladder, compatibility-mode pins, learned stream quirks, the
per-playlist pre-buffer, the external-player toggle. The other entry points each re-implemented a
minimal "resolve the URL and hand it to mpv", so the *same channel* behaved differently depending on
where you clicked it, and a channel that needed any of that knowledge simply failed there.

- **F05 — Prefer HLS (and everything else) applied in exactly one place.** Search tuned straight to mpv
  with the raw `.ts` URL: no HLS swap, no engine ladder, no pin, no quirk learning, no zap list. The
  Guide's own `play()` fallback had the same gap, and Xtream catch-up always requested `.ts` even for an
  account whose panel only serves this profile `.m3u8`.
- **F11 — the HUD's Retry restarted a *movie* as a live stream at 00:00.** `retry()` unconditionally
  called `reloadLive()`: live demuxer settings, live watchdogs, position thrown away — and with a stale
  Stalker reconnect provider still installed it could mint and play a *live channel* instead of the film.
- **F12 — the reconnect provider had the wrong lifetime.** It was a player-global installed only by
  `LiveViewModel`, so it outlived its channel; meanwhile Stalker **VOD** installed none at all, even
  though a `create_link` URL expires in ~2–4 h, comfortably inside a long film.
- **F29 — mpv treated `458` as a hard refusal.** `458` is what Xtream panels invent for *"the account's
  one session is already in use"* — the stream is fine, we are the second client. The ExoPlayer side
  already knew this (`LiveStreamQuirks.isSessionLimit`) and backs off and reconnects; mpv gave up after a
  single repeat. That is precisely the "plays on ExoPlayer, not on mpv" symptom on one-session panels.

### Changes

1. **One live entry point.** `SearchScreen` gained an optional `onPlayChannel`; the shell wires it to
   `LiveViewModel.watchFromGuide()` — the same call the Guide uses — so a channel found in Search now
   gets the full path (engine ladder, pins, quirks, per-playlist pre-buffer, external-player toggle,
   CH+/CH− zap list, immediate History). `SearchViewModel.noteChannelPlayed()` keeps the history +
   recent-search bookkeeping that is genuinely Search's own.
2. **The two standalone fallbacks honour Prefer HLS.** `SearchViewModel.playChannel()` and
   `EpgViewModel.play()` now go through `ChannelEntity.playStreamUrl(source)`. They remain deliberately
   minimal (mpv only) and are reached only when a host mounts those screens without the shared callback.
3. **Catch-up follows Prefer HLS too** (`CatchupUrl.forSource`): `timeshiftUrl(..., ext = "m3u8")` when
   the source prefers HLS, instead of a hard-coded `.ts`.
4. **Retry knows what it is retrying** (`OwnTVPlayer.retry()`): live reloads from the edge as before;
   VOD reloads **as VOD, at `_position.value`**. `reloadLive()` is now a thin wrapper over a shared
   `reload(url, isLive, resetRetries)`.
5. **The reconnect provider is part of the load, not of the player** (F12). `play()` takes an optional
   `reconnectProvider`; a VOD load with none *clears* whatever the previous item left behind (live keeps
   the field when none is passed, because `LiveViewModel` installs it on both engines just before
   playing). `MovieViewModel` passes a Stalker VOD provider that re-mints the movie's `create_link`, and
   `loadItem()` reuses a queue item's own `resolveUrl` as its provider, so a Stalker **episode** — and
   every next/prev/autoplay advance — can re-resolve too.
6. **Three-way classification of mpv's 4xx** (`httpRefusalKind()` → `NONE` / `HARD` / `BUSY`).
   `408`/`429`/`458` are **busy**, not refusal: they keep the full backed-off retry ladder, matching what
   ExoPlayer does. Everything else 4xx stays `HARD` (one repeat, then only the request-*changing*
   fallbacks). A live `458` on mpv now also calls `LiveStreamQuirks.rememberSessionLimit()`, so the next
   tune on that panel already knows the two engines must not overlap. mpv only surfaces the status line,
   so `Retry-After` cannot be honoured — the ladder's own back-off spaces the repeats.

### Files changed

| File | Why |
|---|---|
| `features/search/SearchScreen.kt` | optional `onPlayChannel` → shared live path |
| `features/search/SearchViewModel.kt` | `noteChannelPlayed()` split out; fallback honours Prefer HLS |
| `features/shell/OwnTVShell.kt` | wires Search's channel play to `liveVm.watchFromGuide` |
| `features/live/LiveViewModel.kt` | doc only — `watchFromGuide` is now the shared out-of-Live entry point |
| `features/epg/EpgViewModel.kt` | fallback `play()` honours Prefer HLS |
| `core/epg/CatchupUrl.kt` | Xtream catch-up uses `.m3u8` when the source prefers HLS |
| `player/OwnTVPlayer.kt` | `retry()` VOD branch + shared `reload()`; `reconnectProvider` on `play()`; provider from a queue item's `resolveUrl`; `HttpRefusal` / `httpRefusalKind()` / `httpStatusOf()`; mpv learns the session limit |
| `features/movies/MovieViewModel.kt` | Stalker VOD reconnect provider |
| `core/stalker/StreamUrlResolver.kt` | doc — the provider covers VOD as well as live |
| `test/player/LiveReconnectLadderTest.kt` | hard-refusal test narrowed; new busy-vs-refusal test |

### What the owner should test

1. **Search → a channel → play.** It should behave exactly like playing the same channel from Live TV:
   starts on ExoPlayer (or straight on mpv if that channel is pinned to compatibility mode), CH+/CH− zap
   works, it lands in History, and with **External player** on it opens the external app instead of the
   in-app player.
2. **A Prefer-HLS playlist:** a channel that only works as `.m3u8` must now play from **Search** and from
   the **Guide**, not just from Live TV.
3. **Catch-up on a Prefer-HLS Xtream playlist** — "Watch from start" should still play. (This is the
   riskiest item of the phase: if a panel serves the archive only as `.ts`, catch-up would now fail on a
   Prefer-HLS playlist. Please try it on a playlist that has both catch-up and Prefer HLS enabled.)
4. **Retry on a movie.** Force a failure (pull the network briefly mid-film, or open something the
   provider drops), then press **Retry** on the error: it must resume the *movie* near where it stopped —
   never restart at 00:00 and never jump to a live channel.
5. **Stalker, if you can:** watch a long movie/episode past the point where the portal link would have
   expired (2 h+), or hit Retry late in the film — it should re-resolve and continue instead of erroring.
6. **A one-session panel:** play a channel full-screen on mpv (compatibility mode) right after ExoPlayer
   had it — the 458 case should now recover after a back-off instead of erroring out immediately.
7. **General regression:** normal Live TV zapping, Guide tunes, and Movies/Series playback unchanged.

### Known limits / risks of this phase

- **Catch-up `ext` is the one behaviour change that could break something that worked.** Prefer HLS is a
  live-edge setting; assuming the archive follows it is right for the panels that only allow `m3u8`, but a
  panel that serves archive as `.ts` only would now be asked the wrong way. Item 3 above exists for this;
  it is a one-line revert if it misbehaves.
- `SearchViewModel.playChannel()` and `EpgViewModel.play()` still exist as minimal fallbacks. They are not
  reached from the shell any more, so they can rot; a later phase could delete them outright.
- The Stalker VOD reconnect path is untestable here (no portal) — it is reasoned from the resolver's own
  contract, not observed.

### Owner test result

Owner tested on the TV: **yes, all working.** Search→channel, Prefer-HLS playlists, catch-up and VOD
Retry all behaved as expected; the catch-up `ext` risk above did not materialise on his playlists.

### Next session — start here

Begin **Phase 6** — M3U per-channel headers and the catch-up `append` mode (F16/F17), which **does**
carry a Room migration to **DB 26**. Read `PLAYBACK_AUDIT.md` F16/F17 first, and treat the migration
with the usual care (public baseline vs. dev DB version).

## Phase 6 — M3U specifics (F16 / F17)

**Status: `ok, done, tested`** — owner-confirmed on the TV, including the DB 26 upgrade over the
installed build. `:app:assembleDebug`, the unit-test suite and `:app:assembleStandardRelease` are all
clean; the APK is signed with the real release key (`CN=Ashiq Hasan`, SHA-256 `e8fbd2f2…`).
Room **DB 25 → 26**.

### What this phase fixes, and why

Two M3U-only gaps, both of which make a playlist that works in VLC/TiviMate fail in OwnTV.

- **F16 — per-channel HTTP options were dropped on the floor.** `M3uParser` ignored every directive
  that wasn't `#EXTINF`, and stored the URL line verbatim. So `#EXTVLCOPT:http-user-agent`,
  `#EXTVLCOPT:http-referrer`, `#EXTHTTP:{"cookie":…}`, `#KODIPROP:inputstream.adaptive.stream_headers`
  and the `http://host/x.ts|User-Agent=Foo&Referer=Bar` suffix all vanished. Only a *per-source* UA
  existed, so a playlist where **one** restream needs its own UA/Referer — routine for CDN-token and
  restream playlists — answered 403 with no way for the user to fix it. (The pipe suffix was worse
  than ignored: it was played as part of the URL.)
- **F17 — the catch-up *type* was parsed and thrown away.** `ChannelEntity` stored only
  `catchup: Boolean`, so `CatchupUrl.forSource` passed a hardcoded `null` type and the `append` branch
  inside `forM3u` was **unreachable**. The very common
  `catchup="append" catchup-source="?utc={utc}&lutc={lutc}"` therefore built a URL that was just the
  bare query string, and `{lutc}` wasn't a known token so it reached the provider literally.

### Changes

**New: `core/network/StreamHeaders.kt`** — the one place headers are represented. Stored as `Key:
Value` per line (not JSON: it is what an HTTP request looks like, needs no parser, and is readable in
a backup). Canonicalises the handful of names playlists really use, so `referer` / `Referrer` /
`REFERER` collapse to one entry, and **refuses transport-owned names** (`Host`, `Content-Length`,
`Connection`, `Transfer-Encoding`, `Range`) — a playlist that sets one breaks the request rather than
fixing it. Also formats mpv's `http-header-fields` and extracts the per-channel User-Agent.

**`M3uParser`** parses all four conventions into `M3uEntry.headers`, and strips a `|…` suffix off the
stored URL. Deliberately cheap on the hot path: the three new prefixes are only tested inside the
existing `line.startsWith("#")` branch, so a playlist without them pays exactly what it paid before.
A `|` only counts as a header suffix when it follows a scheme and is followed by a `=`, so a pipe
inside a path stays part of the URL. Headers are held in their own pending slot that is cleared on
each `#EXTINF`, so they can never leak into the next entry.

**`ChannelEntity.httpHeaders` + `ChannelEntity.catchupType`**, filled by `M3uSyncer`.
`computeContentHash()` folds the two new fields in **only when the channel actually has one** —
adding them unconditionally would change every stored hash at once and turn the next resync of a
100k-channel playlist into a full rewrite for everybody, while this way a later header change still
propagates.

**Both engines send them.** mpv sets `http-header-fields` per load (always written, so a channel with
no headers clears the previous one's); `LivePreviewEngine` and `ExoSubtitleEngine` use
`setDefaultRequestProperties`. The live engine's cached data-source/media-source factories are now
keyed on **UA + headers**, not UA alone, so a channel with its own Referer can't reuse the previous
channel's factory. A per-channel `User-Agent` **overrides** the per-source one — a playlist-wide UA is
the general setting, an `#EXTVLCOPT` line is the specific one. The headers also cross the mpv →
ExoPlayer handoff, which additionally fixes a pre-existing gap: `ExoSubtitleEngine` used to force the
default UA, so a stream that needed a custom UA on mpv lost it the moment the fallback engine took
over.

**F17:** `forM3u` now receives the real type and handles `append` (joined onto the live URL, with the
`?`→`&` separator corrected when the channel URL already carries a query — otherwise the result has
two `?` and is rejected outright), `shift`, `flussonic` (`…/index.m3u8` →
`…/timeshift_abs-<start>.m3u8`, `…/mpegts` → `…/archive-<start>-<dur>.ts`) and `xc` (rebuilds the
panel's `timeshift.php` from the credentials in the live URL). `{lutc}`/`{now}`/`{timenow}` are now
substituted with the current time. Live rewind (`buildLiveTimeshiftUrl`) passes the type too.

**Room 25 → 26**: guarded `ALTER TABLE channels ADD COLUMN catchupType TEXT` / `httpHeaders TEXT`,
plus the standing `healSchema(db)` on the final hop. Both nullable, no rewrite of the channels table.
Migration registered in `DatabaseModule`, schema `26.json` exported, migration test at v26 with column
assertions.

### Files changed

| File | Why |
|---|---|
| `core/network/StreamHeaders.kt` | **new** — header canonicalisation, storage format, mpv/UA helpers |
| `core/parser/M3uParser.kt` | `#EXTVLCOPT` / `#EXTHTTP` / `#KODIPROP` / pipe-suffix parsing; `M3uEntry.headers` |
| `core/database/entity/ContentEntities.kt` | `httpHeaders` + `catchupType` columns; conditional content hash |
| `core/sync/M3uSyncer.kt` | stores both new values |
| `core/database/OwnTVDatabase.kt` | version 26, `MIGRATION_25_26` |
| `di/DatabaseModule.kt` | register `MIGRATION_25_26` |
| `core/epg/CatchupUrl.kt` | type passed through; `append` separator fix; `shift`/`flussonic`/`xc`; now-tokens |
| `features/live/LiveViewModel.kt` | headers at all 7 play sites; rewind passes the catch-up type |
| `features/epg/EpgViewModel.kt`, `features/search/SearchViewModel.kt` | headers at their play sites |
| `player/OwnTVPlayer.kt` | `httpHeaders` on `play()`, mpv `http-header-fields`, per-channel UA precedence, carries both into the Exo handoff |
| `player/LivePreviewEngine.kt` | `httpHeaders` on `play()`, factories keyed on UA+headers, rebuild carries them |
| `player/ExoSubtitleEngine.kt` | honours the UA and headers (previously always the default UA) |
| `test/parser/M3uHeaderParsingTest.kt` | **new** — 8 tests over the four conventions + leak/round-trip |
| `test/epg/CatchupUrlTest.kt` | now-tokens, separator fix, `shift`/`flussonic`/`xc` |
| `androidTest/.../OwnTVDatabaseMigrationTest.kt` | `CURRENT_VERSION = 26` + column assertions |
| `app/schemas/.../26.json` | exported schema |

### What the owner should test

Install over the existing app (**keeps all data** — this is also the DB 26 upgrade test):

```powershell
adb install -r app\build\outputs\apk\standard\release\app-standard-release.apk
```

1. **The upgrade itself.** First launch must not crash, and the catalog, playlists, favourites and
   history must all still be there. That is the migration.
2. **No regression on existing playlists.** Live TV, Movies, Series, the Guide, Search and catch-up all
   behave as before. This is the important one: the parser changed, so an M3U playlist should still
   import with exactly the same channel count as before.
3. **The new values only appear after a re-sync.** The migration leaves both columns null on existing
   rows, so an M3U playlist has to be refreshed once before any of this takes effect. Worth doing on
   one playlist and confirming nothing about it changes.
4. **A playlist with a pipe suffix**, if you have one (`http://host/x.ts|User-Agent=…`): the channel
   should now play, where before the whole `|…` was sent as part of the URL.
5. **Catch-up on an M3U playlist with `catchup="append"`** — "Watch from start" should now build a real
   URL. Previously it played nothing at all on those playlists.
6. **Live rewind on an M3U channel** still works.

### Known limits / risks of this phase

- **Headers are a live-channel property only.** M3U *VOD* entries (movies/episodes) can carry the same
  directives, but the audit scoped the column to `ChannelEntity`, and adding it to movies and episodes
  as well would triple the migration. If a report ever shows a 403 on an M3U movie, that is the follow-up.
- **The external player receives no headers** — the intent carries only the URL, so a channel that
  needs a Referer will fail in a third-party player. Unchanged from before, but now inconsistent with
  the in-app behaviour.
- **The `xc` and `flussonic` catch-up styles are written from the published conventions, not observed**
  against a real provider — no playlist here uses them. They are only reached for a channel whose
  `catchup` type says so, and the old code produced *nothing* in those cases, so the downside is
  bounded: a wrong URL instead of no URL.
- **The `append` separator fix is the one behaviour change that could alter something that worked.** A
  provider expecting a literal second `?` (there is no such convention, but panels are panels) would
  now get `&` instead. Item 5 covers it.
- Nothing is populated until a playlist re-syncs, so a user who never refreshes sees no change at all.
- The migration test was **compiled only, never executed** — running instrumentation tests reinstalls
  the app and would wipe the owner's data. It can be run on an emulator if wanted.

### Next session — start here

Nothing outstanding here: Phase 6 is owner-tested and confirmed, including the DB 26 upgrade.

## Phase 7 — Diagnostics, MediaSession, audio focus (F18 / F26-logging / F27 / F28)

**Status: `ok, done, tested`** — owner-confirmed on the TV. One fault was found and fixed during the
test: the log viewer would not scroll (see the last item under "Changes"). `:app:assembleDebug`, the
full unit-test suite and `:app:assembleStandardRelease` all clean; the release APK is signed with the
real key (`CN=Ashiq Hasan`, SHA-256 `e8fbd2f2…`). **No Room migration — the database stays at v26.**
This is the last phase of the audit.

### What this phase fixes, and why

- **F28 — one connection pool for everything.** Every HTTP user shared the single OkHttp client, so
  stopping live playback (which evicts idle sockets to release the provider's session) also dropped the
  keep-alive connections EPG, the panel API, metadata and image loading were relying on. The fix keeps
  one *configuration* and splits the *sockets*.
- **F27 — the app never asked for audio focus and had no MediaSession.** A notification, an assistant
  reply or another app's sound played straight over the film; nothing paused, nothing ducked, and the
  TV's own transport keys (play/pause on a remote, a headset button, "pause" by voice) reached nothing.
- **F18 — the diagnostics a report needs were compiled out of release builds.** The live trace only ran
  in debug/diagnostic builds, and the on-disk log recorded hard failures *only*. A user whose picture
  judders or whose sound drifts has no failure to report, so the log they were asked for came back
  empty. There was also no way to get the log off a TV.
- **F26 (logging half)** — the quality events that explain a complaint (a decode rescue, an engine
  handoff, the stereo safety net firing) were Logcat-only, i.e. invisible to every user.

### Changes

1. **A streaming-scoped HTTP client** (`core/network/StreamingHttpClient.kt`, new). Built with
   `base.newBuilder().connectionPool(…)`, so it inherits the singleton's proxy selector, authenticator,
   DNS, forced HTTP/1.1, default-UA interceptor and timeouts, but owns its sockets. All three streaming
   engines (`LivePreviewEngine`, `ExoSubtitleEngine`, `HeroPreviewEngine`) take it, and
   `releaseHttpConnections()` now evicts *that* pool. OkHttp only evicts idle connections, so nothing in
   flight is harmed, and the diagnostic client derives from it, so it shares the same pool.
2. **Audio focus + MediaSession** (`player/PlaybackSession.kt`, new; wired in `OwnTVShell`). Deliberately
   the *framework* `android.media.session.MediaSession`, not Media3's: mpv is not a Media3 `Player`, so a
   Media3 session would need a `SimpleBasePlayer` adapter — a large, risky change — plus a new dependency.
   The framework session is engine-agnostic and free. It publishes title/artist, duration (`-1` for live)
   and position, and exposes seek/FF/rewind actions for VOD only. Transport callbacks route back into
   whichever `PlaybackEngine` currently owns the speaker; `OwnTVShell` is the only place that knows which
   that is, so it hands the engine over and hands back `null` when the player closes (an idle session must
   not answer a remote's play key).
   **Focus policy is duck-don't-pause**, as chosen: `setWillPauseWhenDucked(false)` lets the platform
   attenuate for a transient-can-duck loss, a plain `LOSS_TRANSIENT` ducks to 25% manually and restores on
   gain, and only a permanent `LOSS` pauses and abandons focus. Nothing pauses a film for a notification.
3. **Detailed playback logging is a runtime setting** (Settings → Video player → Diagnostics). It drives
   `LiveDiagnosticsLog.enabled`, so a release build can now produce the full live trace on request; debug
   and `-PdiagnosticBuild` builds stay on regardless. It is collected inside `LivePreviewEngine.init` —
   *not* at app start — because cold start does no DataStore work.
4. **The playback log records events, not just failures.** `PlaybackErrorLog` gained a `Kind`
   (`ERROR` / `EVENT` / `REPORT`), an `event()` and a `report()` entry point, and its capacity went 10 → 25.
   The JSON reader uses `optString`, so an older file still loads and its entries read as `ERROR`.
   Event call sites are deliberately few and one-shot: the stereo latch on all three engines, both mpv
   decode-rescue rungs, the catch-up archive software rescue, the live 403 → mpv handoff, and a learned
   one-session panel (458) on both engines.
5. **"Report this stream"** — a share button in the player's bottom bar, shown only while the stream-info
   overlay is open (there is nothing to report otherwise). It writes the readout the user is looking at —
   codec, resolution, HDR, bitrate, decoder, audio, buffer, engine, position — into the log as a `REPORT`
   entry, and confirms with the existing bottom-centre toast.
6. **Export.** The log viewer gained an **Export** button that writes the whole log plus the live
   diagnostics ring as plain text to the app's external files dir and shows the path on screen:
   `adb pull /sdcard/Android/data/tv.own.owntv/files/owntv-playback-report.txt`. A TV has nowhere to
   "share" to, so a pullable file is the export that actually works. The viewer also shows each entry's
   kind now, and its own description and search keywords were updated.
7. **The viewer scrolls** (found by the owner during the test). It used the dialog panel's own
   `verticalScroll` with nothing focusable inside it — on a TV that column only moves when focus moves
   into it, so with the capacity raised to 25 the panel simply grew past the screen and the older half was
   unreachable. The entries now live in a height-capped `LazyColumn` whose rows are focusable, the same
   pattern the "Fill from playlist" dialog uses. Focus still starts on **Close**, so Export/Clear/Close
   stay one press away and Up walks back through the history; Back still closes.

### Files changed

| File | Why |
|---|---|
| `core/network/StreamingHttpClient.kt` | **new** — playback-scoped OkHttp client + its own `ConnectionPool` (F28) |
| `di/DataModule.kt` | registers it |
| `di/PlayerModule.kt` | engines take the streaming client; registers `PlaybackSession` |
| `player/LivePreviewEngine.kt` | streaming client, pool-scoped `releaseHttpConnections()`, diagnostics toggle collector, event log on the mpv handoff and on 458 |
| `player/ExoSubtitleEngine.kt`, `player/HeroPreviewEngine.kt` | streaming client; stereo-latch event log (Exo VOD) |
| `player/OwnTVPlayer.kt` | streaming client; event log on both rescue rungs, the archive rescue, the surround failsafe and a learned 458 |
| `player/PlaybackSession.kt` | **new** — audio focus (duck-don't-pause) + framework MediaSession (F27) |
| `features/shell/OwnTVShell.kt` | hands the currently-sounding engine to the session, `null` when the player closes |
| `player/PlaybackErrorLog.kt` | `Kind`, `event()`, `report()`, `export()`, capacity 25 |
| `player/PlayerHud.kt` | "Report this stream" button (only while stream info is open) + its confirmation |
| `features/settings/data/SettingsRepository.kt` | `detailedDiagnostics` flow + setter + backup key |
| `features/settings/SettingsViewModel.kt` | `detailedDiagnostics` state + setter |
| `features/settings/VideoPlayerSettingsScreen.kt` | the Diagnostics row |
| `features/shell/components/SettingsScreen.kt` | log viewer: kind chip, Export, updated copy; search entry for the new row |

### What the owner should test

Install over the existing app (**keeps all data**):

```powershell
adb install -r app\build\outputs\apk\standard\release\app-standard-release.apk
```

1. **Nothing pauses your film.** Trigger a notification or a short system sound during a movie — the
   audio should dip briefly and come back, never pause. If something takes audio permanently (another
   media app), playback should pause.
2. **Transport keys.** If the remote has play/pause (or you can use a headset/assistant), pause and
   resume from it during a movie and during a live channel. Skip/next should move episodes in a series.
   With the player closed, those keys must do nothing to OwnTV.
3. **Zapping still releases the provider session.** On a one-session panel, zap a few channels and switch
   engines — the "session in use" behaviour should be no worse than before. Meanwhile the Guide, images
   and metadata should not feel slower after a zap (that is the point of the split pool).
4. **Report this stream.** Play anything → open the stream-info overlay (ⓘ) → the share button appears →
   press it → "Stream report saved" appears. Then Settings → Playback error log: the entry is there,
   marked **Report**.
5. **Export.** In that dialog press **Export** — a path appears. Then, from a PC:
   `adb pull /sdcard/Android/data/tv.own.owntv/files/owntv-playback-report.txt`
6. **Detailed playback logging.** Settings → Video player → Diagnostics → turn it on, play a live channel,
   then export again: the report should now carry a live diagnostics section.
7. **General regression:** live, movies, series, catch-up, PiP and the mini player all behave as before.

### Known limits / risks of this phase

- **The MediaSession is transport-only.** It is not a foreground media service, so it does not give
  background audio, a lock-screen/now-playing card on every launcher, or playback that survives the
  player screen closing. Nothing in the app claimed those before either.
- **`onStop` from a remote is treated as pause**, not exit: the session must not tear down UI it doesn't
  own.
- **Ducking is manual for `LOSS_TRANSIENT`** — the platform ducks `LOSS_TRANSIENT_CAN_DUCK` itself. On a
  TV that ducks in hardware, the two could stack briefly; it restores on focus gain either way.
- **The split connection pool is the one change that could alter live behaviour**, because it changes what
  a stop evicts. It should only ever help (metadata sockets are no longer collateral damage), but item 3
  is the check.
- **Event logging is best-effort and deliberately sparse.** It is not a substitute for the live trace;
  it exists so a report is never empty.
- The diagnostics toggle only affects *what is written down*. It never changes playback.

### Audit complete — and the follow-up round with it

All seven phases are owner-tested, and so is the follow-up round below (F30/F31/F19a–F19e). Every **S1 and S2** finding in `PLAYBACK_AUDIT.md` is fixed, and every
S3 finding that the three reported issues depended on. What remains uncommitted alongside the audit — the
EPG time offset feature and the catch-up hardware-first decode work — is separate from it but was tested
in the same passes. Next session: documentation (`CHANGELOG.md`, `CHANGELOG_APP.md`,
`extras/USER_GUIDE.md`) and the commit note, per the session-end sequence.

## What the audit deliberately did NOT do

Nothing here is a regression or a half-finished change: these are audit findings that were looked at and
left, each for a stated reason. They are listed so a later session doesn't re-discover them as "bugs".

### Worth doing, just not now (a small follow-up round) — **now done**

This whole group was implemented after the audit closed, in one follow-up round. See
**Follow-up round** below for what each change actually does. Nothing is left in this group.

### Investigated and rejected — do not retry blindly

- **Plan item 24 — `vo=gpu-next` / GL path for live** (frame pacing). Tried twice on the owner's TV in an
  earlier session and both attempts were **clearly worse**; the reasoning and the measurements are written
  into `OwnTVPlayer.kt` above `useDirect()`. The real cure needs a timed `releaseOutputBuffer` inside
  libmpv's Android VO — a native change, not an option we can set.
- **F19h — replace the >1080p software-decode guard with a measured check.** The guard exists because
  software 4K on TV hardware degrades into an unwatchable slideshow rather than a clean failure. A measured
  version needs a spread of real devices to calibrate on; with one TV available it would be guesswork.
- **F19f / F19g — `PlayerBudget` GPU tiering and the unused `demuxerLavfOptionsFor` parameters.** Internal
  tidiness with no user-visible effect. F19f in particular would need device data we don't have.

## Follow-up round (after the audit) — **ok, done, tested**

The seven "worth doing, just not now" items, done in one pass. Status: **owner-tested on TV, all ok —
including the DB 26 → 27 upgrade over an installed build (catalog, playlists, profiles and history kept).**

| # | Change | What it does |
|---|---|---|
| F30 | Xtream `max_connections` read at sync | The panel's own `user_info.max_connections` is stored on the source (`sources.maxConnections`, DB **27**) and, when it is `1`, seeds `LiveStreamQuirks.rememberSessionLimit()` — at sync *and* on every app start from the shared source flow. The app no longer has to burn one failed tune learning what the provider already told it. |
| F31 | Preview pane says why it is off | On a one-session provider whose stream is already in use, the pane shows "Preview off — your provider allows one stream at a time" instead of sitting dead, which read as a broken channel. |
| F19c | Audio Mode really releases the video decoder | Dropping the surface only stopped the *drawing* — ExoPlayer kept decoding every frame. Audio Mode now disables the video track too (and re-applies that after a player rebuild). |
| F19d | Home hero preview: one-session guard + it learns the 458 | The hero preview now skips playback when mpv already holds the provider's single session, and a 458 there feeds `rememberSessionLimit()` like the live engine's does. |
| F19e | A/V-sync nudge on live | The nudge is now offered wherever the engine can actually shift audio (`PlaybackEngine.audioDelayAvailable()` → true on mpv), instead of on VOD only. Still hidden when live runs on ExoPlayer, which has no `audio-delay`. |
| F19b | mpv subtitles in the docked mini-player | `SubtitleOverlay` gained a `sizeScale` and is composed docked as well as full-screen, scaled to the chosen mini size (floored so the smallest box stays readable). |
| F19a | Volume boost above 100% on Live TV | 100–150% now comes from a platform `LoudnessEnhancer` on the ExoPlayer audio session (≈ +3.5 dB at 150%, matching mpv), not from player gain — ExoPlayer cannot amplify past unity and the old gain-processor attempt broke the audio sink. Released as soon as the volume is back at 100, re-bound after a player rebuild, and a device that refuses the effect just stays at unity. Needs `MODIFY_AUDIO_SETTINGS` (normal permission, no prompt). |

### Files changed

| File | Why |
|---|---|
| `core/database/entity/ProfileEntities.kt`, `core/database/OwnTVDatabase.kt`, `di/DatabaseModule.kt` | `sources.maxConnections` + `MIGRATION_26_27` (additive, guarded, carries `healSchema`) at DB version 27 |
| `core/database/dao/SourceDao.kt`, `core/parser/XtreamClient.kt`, `core/sync/XtreamSyncer.kt` | parse and persist `user_info.max_connections` |
| `core/repository/ActiveSources.kt` | seeds the one-session quirk from the stored value on every source emission |
| `features/live/LiveViewModel.kt`, `features/live/LiveScreen.kt` | the "preview off" state and its hint |
| `player/HeroPreviewEngine.kt`, `di/playerModule.kt` | hero one-session guard (`streamInUse`) + 458 learning |
| `player/LivePreviewEngine.kt` | video-track disable in Audio Mode; the LoudnessEnhancer boost |
| `player/PlaybackEngine.kt`, `player/PlayerHud.kt` | `audioDelayAvailable()` gating for the A/V-sync nudge |
| `player/SubtitleOverlay.kt`, `features/shell/OwnTVShell.kt` | scaled subtitles, drawn docked too |
| `AndroidManifest.xml` | `MODIFY_AUDIO_SETTINGS` for the boost effect |
| `app/schemas/…/27.json`, `androidTest/…/OwnTVDatabaseMigrationTest.kt` | exported schema + `CURRENT_VERSION = 27` and the new column assertion |

### Owner test steps — all passed

1. **Upgrade path** — install the release APK over the existing app with `adb install -r` (never uninstall)
   and confirm the catalog, playlists, profiles and history survive the DB 26 → 27 migration.
2. **Xtream sync** — run a playlist refresh on an Xtream source; on a one-stream account, start a channel
   full-screen and move focus in the list: the preview pane should show the "one stream at a time" hint
   rather than a dead box, on the *first* attempt after a fresh start.
3. **Home hero** — with a channel playing on a one-stream account, go Home: the hero shows the poster and
   playback keeps running (no 458 knock-off).
4. **Audio Mode** on a live channel — audio keeps playing, picture stops; leaving Audio Mode brings the
   picture back cleanly.
5. **A/V-sync** — open Audio → the ± nudge is now present on a live channel *in compatibility (mpv) mode*
   and still absent on a normal ExoPlayer live channel.
6. **Mini-player subtitles** — play a subtitled film, dock it: subtitles appear, sized to the box.
7. **Volume boost on live** — push volume past 100% on a quiet live channel: it should get louder up to
   150%, with no crackle or audio dropout; back at 100% it must sound exactly as before.
