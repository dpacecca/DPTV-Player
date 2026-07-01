<p align="center">
  <img src="extras/logo.png" alt="OwnTV" width="360">
</p>

<p align="center">
  <b>Your own IPTV player for Android TV</b><br>
  <sub>Fast · modern · remote-first — bring your own M3U or Xtream sources</sub>
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Compose for TV" src="https://img.shields.io/badge/Jetpack%20Compose-for%20TV-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="Player" src="https://img.shields.io/badge/engines-libmpv%20%2B%20ExoPlayer-FB8C00">
  <img alt="License" src="https://img.shields.io/badge/license-GPLv3-blue">
  <img alt="Built with the help of AI" src="https://img.shields.io/badge/built%20with-the%20help%20of%20AI-8A2BE2">
</p>

<p align="center">
  <a href="https://github.com/ahXN00/OwnTV/actions/workflows/android.yml">
    <img alt="Android CI" src="https://github.com/ahXN00/OwnTV/actions/workflows/android.yml/badge.svg">
  </a>
</p>

---

OwnTV is a native **Android TV** IPTV **player** built with Kotlin, Jetpack Compose for TV, and a
**dual playback engine** — **libmpv (FFmpeg)** for movies/series and maximum compatibility, **ExoPlayer
(Media3)** for near-instant Live TV. It's a *player only* — you bring your own Xtream login or M3U playlist
(by **URL or a local `.m3u`/`.m3u8` file** on the device), and OwnTV gives you a fast, modern, remote-first
way to browse
and watch them.

> ⚠️ OwnTV does **not** provide any channels, playlists, subscriptions, streams, or media content.
> You are responsible for adding your own legally accessible sources.

This is an **open-source** project — the code is original (not derived from any other app) and was
**built with the help of AI**. **Contributions are welcome**: clone, build, and test it freely. It
targets **Android TV only** (leanback launcher, D-pad-first UI).

> ### 📖 New here? Read the [**User Guide & Hidden Features →**](extras/USER_GUIDE.md)
> Long‑press to favourite, **Left** for the channel list, the gear button's **compatibility mode**,
> catch‑up from the Guide, startup landing, A/V‑sync — all the remote shortcuts in one place.

---

## 💬 Community

Questions, ideas, bug reports — or just want to follow along? **Join the OwnTV Telegram group:**

### 👉 [t.me/owntvplayer](https://t.me/owntvplayer)

Scan to join from your phone:

<a href="https://t.me/owntvplayer"><img src="extras/telegram_qr_code.jpg" alt="Scan to join the OwnTV Telegram group" width="170"></a>

---

## ✨ Features

### 🎬 Playback
- **Dual-engine design**
  - **libmpv (FFmpeg)** — movies, series, and any stream ExoPlayer can't open; maximum codec/container compatibility, every audio/subtitle track
  - **Media3 (ExoPlayer)** — Live TV default; near-instant HLS start, instant preview → fullscreen
  - **Per-channel mpv toggle** — the gear pins a problem channel to mpv (remembered per channel)
- **Direct-to-display rendering** — zero-copy 4K HDR path, app-drawn subtitles, auto software-decode fallback
- **Channel zapping** — D-pad/CH±/media keys, wraps around; **in-player channel list** (Left with controls hidden)
- **Audio** — surround sound (opt-in, Dolby/DTS → multichannel LPCM, auto-stereo safety net); volume boost to 150%; A/V sync nudge
- **Subtitles** — text (SRT/ASS) + image (PGS/VOBSUB/DVB on its own layer) + closed captions (CEA-608/708)
- **Resume & auto-play** — per-title resume prompt, auto-play next episode (across seasons), opens on last-watched episode
- **Mini-player / PiP** — dock any stream and keep browsing
- **Stream info overlay** — live codec · resolution · fps · HDR · bitrate · decoder readout
- 📺 **[Complete player design & feature reference →](extras/player.html)**

### 🧭 Browse
- **Home screen** — Continue Watching hero carousel (partially-watched movies, episodes, recent channels); feeds system Watch Next on stock Android TV
- **Sections** — Live TV (preview + real stream resolution badge), Movies, Series, Downloads, EPG Guide
- **Fixed layout** — stable icon nav · full-label category column · content · preview (never expands/collapses)
- **Categories** — Favorites & History per section, full names, search box; customize (hide/rename/reorder, range-select); survives re-syncs
- **Search** — inline per-folder + global; TV-style bars; detailed channel results (category · number); long-press to favorite
- **Sort & view** — playlist order or A–Z; Movies & Series Grid/List toggle
- **Per-profile startup** — Home, last channel, or Live TV on Favorites
- **Built for scale** — ~50k channels / ~168k movies via Paging 3

### 🗓️ EPG / TV Guide
- **Guide grid** — time × channel (XMLTV); now/next/later in preview; two-stage nav (Right selects row, OK browses)
- **Catch-up TV** — watch aired programmes (up to 7 days back); seekable archive replay; catch-up time setting
- **Live rewind** — timeshift on catch-up channels; scrubbable timeline + 30s steps + Live button
- **EPG matching** — auto-match by name (ignoring HD/country tags); long-press for manual match; survives re-syncs
- **Sort & filter** — A–Z / Provider / Live TV / Catch-up / Favorites; category filter with search
- **Multiple sources** — add/edit/delete XMLTV feeds; merge into guide; opt-in (pre-fills playlist URL)
- **Performance** — pre-loaded at startup; only your channels' programmes stored; malformed-tag tolerant

### 👥 Profiles
- Multiple profiles (own favorites/history/resume); PIN locks (salted hash); kids flag; "Who's watching?" gate; shared sources

### ⬇️ Downloads
- Offline movies & episodes (never Live TV); pause/resume; user-chosen folder; states shown in grids

### 🎨 Personalization & Settings
- **Appearance** — Material 3 (dark/light/system); any accent color (palette or hex); UI zoom; avatars; animations toggle
- **Content** — clear watch history (all or per-type)
- **Video Player** — hardware decoding, zoom, subtitle size/language, audio sync, surround sound, HDR
- **Backup & Restore** — profiles, sources, customizations, favorites, history, resume, settings; choose what to include
- **Updates** — in-app from GitHub Releases; auto-check (toggleable) + manual; installs APK on TV
- **Android TV home** — feeds system Watch Next row; refresh button

### 🛡️ Robustness
- **Memory-safe** — device-scaled buffers; decode watchdog (4K/8K SW guard); background release; cache shedding
- **No ANRs** — all player commands off the UI thread; coalesced preview-scroll loads
- **Connection-friendly** — preview→fullscreen reuses stream; auto-reconnect on drops
- **Resilient imports** — HTTP 512 / truncated list → per-category fallback; credentials never shown
- **Offline detection** — banner + offline-aware error messages

---

## 📸 Screenshots

<table>
  <tr>
    <td align="center"><img src="extras/screenshots/Home.png" alt="Home screen"><br><sub>Home — Continue Watching</sub></td>
    <td align="center"><img src="extras/screenshots/Main_View.png" alt="Main view"><br><sub>Main view</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="extras/screenshots/LiveTV_with_PreviewON.png" alt="Live TV with preview"><br><sub>Live TV — preview playing</sub></td>
    <td align="center"><img src="extras/screenshots/Movies.png" alt="Movies"><br><sub>Movies</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="extras/screenshots/Profile_Selection.png" alt="Profile selection"><br><sub>"Who's watching?" profile gate</sub></td>
    <td align="center"><img src="extras/screenshots/Downloads.png" alt="Downloads"><br><sub>Downloads</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="extras/screenshots/Settings_Main.png" alt="Settings"><br><sub>Settings</sub></td>
    <td align="center"><img src="extras/screenshots/EPG_loaded.png" alt="TV Guide"><br><sub>TV Guide (EPG)</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="extras/screenshots/Settings_Personalization.png" alt="Personalization"><br><sub>Personalization</sub></td>
    <td align="center"></td>
  </tr>
</table>

More in **[extras/screenshots/](extras/screenshots/)** — Live TV (preview off), playlist management,
profiles & sources settings.

---

## 🧱 Tech stack

| Area | Choice |
|------|--------|
| Language | Kotlin 2.3.10 (AGP 9 **built-in Kotlin**, no `kotlin-android` plugin) |
| Build | AGP 9.2.1 / Gradle 9.4.1, KSP2 2.3.9 |
| UI | Jetpack Compose for TV (`androidx.tv:tv-material` 1.1.0), Compose BOM 2026.05.00 |
| Media | **libmpv** (FFmpeg) — `dev.jdtech.mpv:libmpv` · **ExoPlayer/Media3** (Live TV + image subs) |
| Database | Room 2.8.4 + Paging 3.5.0 + FTS4 (WAL) |
| DI | Koin 4.1.1 |
| Networking | OkHttp |
| Images | Coil 3.3.0 |
| Preferences | DataStore |

`minSdk 26`, `targetSdk 36`, `applicationId tv.own.owntv`.

> **Build note:** Kotlin comes from AGP 9's built-in Kotlin (no `kotlin-android` plugin). KSP 2.3.6+
> supports built-in Kotlin, so Room codegen works alongside it; the Compose compiler and KSP track
> Kotlin 2.3.x.

## ⚙️ How it works (backend)

- **Parsing & sync** — M3U playlists are line-streamed and Xtream `player_api` JSON is read with
  `android.util.JsonReader`, so huge provider payloads are never fully buffered. `SyncManager` does a
  clear-then-insert refresh in ~500-row chunked transactions with Flow progress and cancellation.
- **Storage** — a 19-entity Room schema: profiles & sources (`ProfileSourceCrossRef` for sharing),
  content (categories/channels/movies/series/seasons/episodes), per-profile favorites/history/progress/
  downloads, EPG channels/programmes, and FTS4 search tables. Totals come from indexed `COUNT` queries.
- **Lists** — Paging 3 with a bounded `maxSize` keeps memory flat across 50k+ item lists.
- **EPG** — bulk XMLTV is stream-parsed (gzip-aware) into a rolling now→+48h window and pruned.
- **Player** — two engines behind a small `PlaybackEngine` interface: **libmpv** (movies/series, and any
  live stream ExoPlayer can't open) and **ExoPlayer/Media3** (Live TV — instant HLS). Live "promotes" the
  running preview straight to full-screen (no reload); the shell hoists the active surface across full ↔
  mini-player. Player state is published as `StateFlow`s for the Compose HUD.
- **DI** — Koin modules (`appModule`, `databaseModule`, `dataModule`, `playerModule`).

### Project layout

```
tv.own.owntv/
├── core/        database (Room), network, parser (M3U/Xtream/XMLTV), repository, sync, util
├── player/      libmpv + ExoPlayer engines (PlaybackEngine) + Compose surfaces + HUD + mini-player
├── ui/          theme + reusable components (focus surface, cards, state views, avatars)
├── features/    setup, shell, live, movies, series, search, downloads, epg, profiles, settings
└── di/          Koin modules
```

## 📚 Docs & design (`extras/`)

- 📄 **[Complete Feature Document](extras/OwnTV_Complete_Brief_Plan_With_Logo.docx)** — the full
  as-built feature reference: playback, browse, EPG, profiles, architecture, and tech stack.
- 📺 **[Player design reference](extras/player.html)** — an interactive Material 3 mockup of the player UI.
- 🖼️ `extras/logo.png` — the OwnTV logo.

## 📥 Installing (Fire TV / Android TV)

Grab the signed APK from the [**latest release**](https://github.com/ahXN00/OwnTV/releases/latest) and
sideload it. A fixed link always points at the newest signed build:

```
https://github.com/ahXN00/OwnTV/releases/latest/download/OwnTV.apk
```

- **Fire TV** — install the **Downloader** app (by AFTVnews) from the Amazon Appstore, then enter the
  **Downloader code `4308278`** (or [`aftv.news/4308278`](https://aftv.news/4308278), which always
  points at the latest signed `OwnTV.apk`). Enable *Apps from Unknown Sources* if prompted.
- **Android TV / Google TV** — the **Downloader** app is also on Google Play, so the same code
  **`4308278`** works here too. (If Downloader doesn't show in search, open the Play Store on the TV —
  you can reach it via *Settings → Apps → See all apps → Show system apps → Google Play Store* — and
  install it from there.) Or just sideload the APK with your tool of choice (*Send files to TV*, a USB
  drive, or `adb install OwnTV.apk`).

> Only install the APK from this repository's official Releases (or the `…/releases/latest/download/OwnTV.apk`
> link above). It's the build signed by this project's CI — third-party re-hosts aren't endorsed.

## 🛠️ Building & running

1. Open the project in **Android Studio** (a version matching AGP 9.x) and let Gradle sync.
2. Run the `app` configuration on an **Android TV** emulator or device. The app declares a
   `LEANBACK_LAUNCHER` **and** a regular `LAUNCHER` entry and marks the leanback feature **optional**, so
   it shows in the **TV launcher** on Android TV and as a normal app icon on phones/tablets and non-TV
   boxes too (minimum **Android 8.0 / API 26**).
3. Or from the command line:

```bash
./gradlew assembleDebug
```

On first launch you'll go through onboarding: accept the disclaimer, create a profile, then **add a
source** (M3U or Xtream) — or import a backup. After it imports, browse from the sidebar and open the
**Guide** for the EPG. Everything is managed under **Settings**.

**Tested on:** a real **TCL Google TV**, and the **Android Studio emulator** (both the Android TV and
Google TV system images).

## 🤖 CI & releases

GitHub Actions ([`.github/workflows/android.yml`](.github/workflows/android.yml)) builds the app in the
cloud — no local build needed:

- **Every push / PR** → builds a debug APK and uploads it as a workflow **artifact** named
  `OwnTV-v<version>-<sha>.apk` (download it from the run's *Summary → Artifacts*).
- **Push a `v*` tag** (e.g. `git tag v1.1.0 && git push origin v1.1.0`) → builds a **signed** APK and
  publishes a **GitHub Release** with `OwnTV-v1.1.0.apk` attached. The release notes are taken from
  the newest section of [`CHANGELOG.md`](CHANGELOG.md) (which the in-app updater shows as
  "What's new"), plus GitHub's auto-generated commit list.

**Versioning is automatic**: `versionName`/`versionCode` are derived from the tag (e.g. `v1.1.0` →
`1.1.0` / `10100`) — no need to touch `build.gradle.kts`.

**Signed release builds (optional, recommended for distribution).** Tag builds are debug-signed until you
add a release keystore. Create one and add four repo **Secrets** to get properly signed releases:

```bash
keytool -genkey -v -keystore owntv.keystore -alias owntv -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 owntv.keystore   # copy the output into the KEYSTORE_BASE64 secret
```

Then add repo Secrets (*Settings → Secrets and variables → Actions*): `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Keep the keystore file private — it's never committed.

## 🤝 Contributing

Contributions, bug reports, and ideas are welcome — open an issue or a pull request. Please keep the
project's player-only, bring-your-own-source positioning, and match the existing code style.

## 💛 Support the project

OwnTV is — and will **always be** — completely **free, 100% open-source, and ad-free. Forever.** ❤️ No
paywalls, no "Pro" tier, no catch.

It's a passion project built in my spare time. If it's made your TV a little nicer and you'd like to say
thanks or **buy me a coffee**, a small **PayPal** tip is hugely appreciated — but it's **100% optional**, the
app stays free for everyone no matter what. 🙂

### ☕ [paypal.me/AshiqHasan](https://paypal.me/AshiqHasan)

Scan to donate from your phone:

<a href="https://paypal.me/AshiqHasan"><img src="extras/paypal_qr.jpg" alt="Scan to donate via PayPal" width="170"></a>

Thank you for using OwnTV! 🙏

## ⚖️ Legal

OwnTV is a media **player** only. It ships with no channels, playlists, subscriptions, or content, and
does not endorse or facilitate access to unauthorized streams. Users are solely responsible for the
sources they add and for complying with the laws and rights that apply to them.

## 📄 License

Released under the **GNU General Public License v3.0 (GPLv3)** — see [LICENSE](LICENSE).

In short: you're free to use, study, modify, and redistribute OwnTV, including commercially — but any
redistributed version (including forks and commercial products built on it) must also be licensed under
GPLv3 and its source made available.

---

<sub>OwnTV is an open-source, player-only project, built with the help of AI.</sub>
