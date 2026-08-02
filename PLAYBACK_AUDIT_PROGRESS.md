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
| 2 | VOD decode ladder (BouldozeR) | not started |
| 3 | Live latency drives the Exo LoadControl + pre-roll gate (ntas-sys) — DB 25 | not started |
| 4 | AFR: 25fps@60Hz prompt, fps measurement decoupled from the stats toggle | not started |
| 5 | One shared live path (Prefer-HLS everywhere), VOD Retry, Stalker VOD reconnect, 458 | not started |
| 6 | M3U per-channel headers + working catch-up `append` — DB 26 | not started |
| 7 | Diagnostics, MediaSession, audio focus (duck-don't-pause), connection-pool scoping | not started |

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

### Next session — start here

1. Add the `off` / `disable` search keywords to the surround row (see the "Off option" note above).
2. Then begin **Phase 2**.

---

## Phase 2 — VOD decode ladder (BouldozeR)

Not started. See `PLAYBACK_AUDIT.md` §8 — findings F26 (the missing `0xfffffff4` mapping, a one-line
fix that alone explains the wrong error text), F08 (MOOV-AT-END mislabels decoder failures and then
dead-ends), F09 (re-introduce `hwdec=mediacodec-copy` **as a rescue rung only, never a default** —
approved by the owner), F10 (video capability pre-check before claiming a format is unsupported).

## Phase 3 — Live latency actually drives buffering (ntas-sys)

Not started. F06 / F07. Adds a Room migration to **DB 25** (independent of Phase 6's DB 26 — the owner
confirmed separate migrations per phase are preferred, since the end state is identical either way).

## Phase 4 — AFR and the 25 fps judder

Not started. F13 / F14.

## Phase 5 — One shared live path

Not started. F05 / F11 / F12 / F29.

## Phase 6 — M3U specifics

Not started. F16 / F17. Room migration to **DB 26**.

## Phase 7 — Diagnostics, MediaSession, audio focus

Not started. F18 / F26 (logging half) / F28. Audio focus uses the **duck-don't-pause** design the owner
chose. Last in the queue by the owner's decision.
