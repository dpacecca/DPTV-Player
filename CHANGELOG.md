# Changelog

## v3.1.0 — 2026-06-20

### ✨ New features

- **Catch-up straight from Live TV** — focus a catch-up channel in **Live TV** and the preview now has a
  **Catch-up** button: it opens a simple list of recent programmes — pick one and it **replays from the
  start**. No more hunting through the Guide timeline. (The Guide still works for browsing too.)
- **Hide/show a whole range of categories at once** — in **Settings → Customize**, long-press a category's
  Show/Hide button to start a span, then press Show/Hide on another category to select everything in
  between and hide or show it all in one go — a big time-saver for providers with hundreds of categories.
  (by @dan-maloney, #20)
- **Auto-play next episode** — when an episode finishes, OwnTV automatically starts the next one, and
  **rolls into the next season** after a season's last episode — great for binge-watching. There's a new
  **Settings → Auto-play next episode** toggle (on by default) for anyone who prefers manual playback. (#21)
- **Series open on your last-watched episode** — reopening a show now jumps straight to the episode you
  last watched (correct season, scrolled into view and focused) instead of always starting at episode 1,
  and that episode is tagged **"Last watched"** so it's easy to spot. (#22)
- **Surround sound passthrough** — a new **Settings → Surround passthrough** toggle sends **Dolby
  (AC-3/E-AC-3, incl. Atmos) and DTS** audio straight to your TV or AV receiver to decode, instead of
  mixing down to stereo. OwnTV only passes through the formats your audio output reports it can handle,
  and you can switch it off if a stream goes silent. (Off by default.)

### 🐛 Bug fixes

- **Faster channel zapping** — live channels and HLS streams now start with a **trimmed stream probe**,
  so the picture comes up noticeably quicker when switching channels. If a trimmed probe ever misses a
  stream's audio (rare, on sparse feeds), OwnTV automatically **re-probes that channel in full** so it
  still plays with sound. On-demand movies/series keep the full probe for rock-solid HDR/audio detection.
- **Live channels that dropped out every few seconds now play continuously** — some live servers close the
  connection on a schedule (common with 4K feeds); OwnTV now **reconnects automatically at the stream level**
  and keeps playing, instead of stalling and re-buffering on a loop.
- **Smoother video on TVs** — the player now asks the display to **match the video's frame rate** (e.g.
  switch a 60 Hz panel to 24/48 Hz for 24fps content). On TVs that support it, this removes the subtle
  *judder* of film-rate content on a fixed 60 Hz screen (the "looks slightly slow/uneven, but not
  buffering" feel). No effect on panels that can't switch — it just stays as-is.
- **Installs on non-TV devices now** — OwnTV required the Android **TV (leanback)** feature, so it
  wouldn't install on plain phones / non-TV boxes (incl. some armv7a Android 11 devices) and showed
  **no launcher icon** on phones. It's now installable on regular Android too, with a normal home-screen
  icon — while still appearing in the TV launcher on Android TV. (Also resolves #16.)
- **EPG sources that failed with a "protocol error" now load** — some EPG/host CDNs have flaky HTTP/2
  and would reset large downloads (e.g. a big US guide) with *"stream was reset: PROTOCOL_ERROR"*.
  OwnTV now uses HTTP/1.1 for its downloads, which those servers handle reliably. (#17)
- **Image-based subtitles now play smoothly** — text subtitles (SRT/ASS) display on the fast HDR path as
  before. **Image-based** subtitles (PGS/VOBSUB/DVB) on **movies & series** now display *without* slowing
  the video down: picking one seamlessly hands that title to a second engine (ExoPlayer) that keeps the
  picture on the same zero-copy/HDR path and draws the bitmap subtitle on its own layer — no more stutter,
  and still only **one** connection to your provider. (The old approach composited inside the video and
  could make 4K/HDR unwatchable on TV hardware — that's gone.) Image tracks are tagged **"image"** in the
  picker; turning subtitles off or choosing a text track hands straight back. If a title's audio is a
  format the second engine can't play (e.g. DTS), it stays on the main engine and tells you. (Image
  subtitles aren't shown on live channels, where they're virtually never present.)
- **Big-library import no longer gets stuck** — the per-category fallback (for providers that truncate
  the bulk movie/series list, #15) used to make the import counter look like it was *restarting* each
  category, and on panels that **ignore the category filter** it could loop forever re-fetching the same
  list. Progress now climbs **continuously** across the whole import, and the fallback **stops** when the
  provider clearly isn't honoring per-category requests (keeping everything fetched so far). (#15)

## v3.0.0 — 2026-06-17

*Big release — bundling the open feature requests + Catch-up TV.*

> 💬 **Join us on Telegram** — **Settings → About** now shows the OwnTV **Telegram group** link with a
> **QR code** you can scan from your phone to join the community (also added to the README).

### ✨ New features

- **Browse the TV Guide timeline** — navigating the guide is now two-stage: press **Right** on a channel
  to select its **whole programme row**, then **OK** to step in and move through programmes with
  **Left/Right** (the row scrolls with you). **OK** on a programme opens it (watch / *Watch from start*
  for catch-up), and **Up/Down** jumps to the next channel at the same time. **Back** steps back out.
- **Catch-up TV (archive)** — for providers that offer it, the TV Guide now lets you **watch programmes
  that already aired**. When you have catch-up channels, the guide extends **back in time** (up to ~2
  days, depending on your EPG) — scroll **left** to reach earlier programmes, open one and pick **Watch
  from start** to replay it from the archive (seekable, with a progress bar). The guide opens at *now*,
  with past shows to the left. Works with Xtream (`tv_archive`) and M3U playlists with `catchup` tags.
  If catch-up plays the wrong programme, **Settings → Playback → Catch-up time** lets you set the
  timezone it uses — your **device's**, or a **manual UTC offset** (UTC−12…+14) — that your provider needs.
- **Auto-match your channels to the guide** — the TV Guide has a new **Auto-match EPG** button that
  links channels whose tvg-id is missing or doesn't line up with your EPG feed by matching them **by
  name** (ignoring HD/country tags etc.). Confident matches are applied automatically; the rest are
  shown in a quick **review** list to accept or skip (with **Accept all** / **Skip all** shortcuts).
  Matches are saved per profile and survive re-syncing. (Fixes #13.)
- **Match a channel's EPG from the Guide** — **long-press a channel** in the TV Guide, then choose
  **Auto-match** (match just that channel by name) or **Pick manually** (choose its guide channel from
  the full list, or clear the override). The choice is saved per profile and survives re-syncing. (Fixes #10.)
- **See what's coming up in Live TV** — the channel info overlay now shows a **"Later"** row with the
  next few programmes after *Now/Next*, so you can see the upcoming schedule without opening the Guide.
  (Fixes #11.)
- **Change channels with the D-pad** — while watching a channel fullscreen with the controls
  hidden, **D-pad Up/Down** — plus the **media ⏮/⏭** keys and **CH+/CH−** — now switch channels, so
  remotes without dedicated channel buttons (e.g. Fire TV) can zap too. When the controls are showing,
  Up/Down navigate them as before. Zapping also **wraps around** — past the last channel it loops to the
  first (and vice-versa) instead of dead-ending. (Fixes #9.)
- **Sort the TV Guide** — the Guide has its own **sort** button: **A–Z**, **Provider** order, **Live TV**
  (mirrors your Live TV sort), or **Catch-up** (channels with archive first, so you can find them fast).
  (Fixes #12.)
- **See a channel's real resolution before you watch** — the Live TV preview now shows the **actual
  stream resolution** (e.g. `1080p`, `720p`, `4K`) as a badge on the preview, so a channel named
  "…4K" that's really 1080p no longer fools you.

### 🐛 Bug fixes

- **New playlists show up immediately** — after deleting a playlist and adding another, Live TV / Movies /
  Series now refresh **right away** instead of staying empty until you restarted the app.
- **Huge playlists import fully again** — some Xtream panels cut off very large movie/series lists
  mid-download, which aborted the whole import with an *"Unterminated string…"* error and left you
  unable to sign in. Now, if the bulk list truncates, OwnTV automatically **fetches it category by
  category** (small requests the server can handle) so you get your **full library** — and items keep
  populating as it goes. (Fixes #15.)
- **Faster channel switching in Live TV** — switching channels no longer feels slow or briefly "broken".
  The player now recognises that the *previous* stream's cleanup isn't the *new* stream failing, so it
  skips the needless retries/backoff (and the occasional false "Couldn't play this stream" flash) that
  could delay the preview. The Live preview pane also shows a **loading spinner** while a stream is
  opening. *(Thanks to **[@codeVerine](https://github.com/codeVerine)** — PR #14.)*
- **Left from the channel list returns to your category** — pressing **Left** into the category rail now
  lands on the folder you're actually in (e.g. the current channel's category) instead of jumping to the
  search box at the top. The category search is still there — press **Up** from the top category to reach it.
- **"Now watching" card shows the right channel** — the channel info card no longer keeps the *previous*
  channel's name after a quick zap; it updates the instant the stream changes. (#9)

## v2.2.4 — 2026-06-14

- **Back from a series returns to the right poster** — pressing **Back** inside a series (or its
  on-screen back button) now puts focus back on the **series you opened** in the grid instead of jumping
  to the sidebar (it now scrolls to and focuses it, matching how Movies already behaves).
- **No more sidebar flicker in Settings** — moving between a Settings sub-screen (Playlists, EPG,
  About…) and the Settings menu no longer makes the left rail briefly expand and collapse; it only
  expands once focus actually settles on it. (The sidebar is shared, so this covers every section.)
- **…and no category-rail flicker** — the same settle-before-expand fix now applies to the **category
  rail** (Live TV / Movies / Series), so it no longer briefly widens then collapses when focus passes
  through it during a screen transition.

## v2.2.3 — 2026-06-14

> 🔁 **Please re-sync your playlists after updating.** This release switches live channels to the more
> widely-supported **MPEG-TS** stream format — but each channel's link is built when you sync, so your
> existing channels keep the old format until you re-sync. Open **Settings → Playlists** and press
> **Re-sync** on each one so every channel picks up the change.

- **Channels that wouldn't load now play** — live streams use the universal **MPEG-TS (`.ts`)** endpoint
  instead of HLS (`.m3u8`); some Xtream providers only serve raw MPEG-TS and don't offer the `.m3u8`
  wrapper, so their channels failed to load entirely. And if a `.ts` channel still won't start, the
  player now **automatically falls back to the `.m3u8` variant** before erroring — so the rare HLS-only
  panel keeps working too.
- **Back hides the player controls first** — while watching, when the player UI is showing, **Back** now
  just hides it instead of leaving the channel; press **Back** again (with the controls hidden) to exit
  the player.
- **Smarter playback retries** — when a stream stalls, the silent auto-retry now uses **exponential
  backoff** (1s · 2s · 4s) to better ride out cold-boot decoder lag, **skips retrying when you're
  offline** (shows a "No internet" message immediately instead of spinning), and **fails faster on
  movies/episodes** — a bad VOD link errors after one try instead of three.
- **Channel zapping from the Guide** — the **CH+ / CH−** keys now surf channels while watching a channel
  opened from the **TV Guide**, stepping through the guide's channel list — just like from the Live TV
  list.

## v2.2.2 — 2026-06-14

- **Category rail highlight follows your focus** — the rail no longer keeps your current category lit
  up when you're not on it (while you're on the sidebar, on the new category-search box, or arrowing
  past other categories). Now only the pill you're focused on is highlighted, and your active category
  turns green the moment you land on it — so there's always exactly one highlight, right where the
  remote is.

## v2.2.1 — 2026-06-14

- **Search your categories** — the category rail (Live TV / Movies / Series) now has a **search box**
  at the top. Opening the rail lands right on it, so you can **type to filter** hundreds of categories
  by name and jump straight to the one you want instead of scrolling; **Down** drops into the list. The
  filter clears when you leave the rail.

## v2.2.0 — 2026-06-14

- **Multiple EPG sources** — EPG is now its own thing: **Settings → EPG Sources** lets you add any
  number of XMLTV guide feeds (with **Edit · Delete · Re-sync**), and they merge into the TV Guide.
  Adding a playlist **auto-syncs its EPG** (Xtream `xmltv.php` / M3U `url-tvg`), and the new-source
  message now breaks down what was imported — e.g. *"40K channels · 100K movies · 30K series · 30K
  EPG synced"*. The Guide's manual download button is gone (EPG syncs on add); when there's no EPG it
  shows an **Add EPG** shortcut.
- **Match a channel to a guide manually** — when a channel doesn't auto-match the EPG, open it in the
  Live preview and press **Match EPG** to pick its guide channel (searchable). Saved per profile,
  survives re-syncs; the Guide grid and the now/next card both honor it.
- **"What's New" before updating** — the startup update card now opens the **full changelog** when you
  press *What's New*, matching the manual check — so both paths show what changed before you update.
- **Back up your settings too** — Backup & Restore gained an **App settings** section (theme, accent,
  UI zoom, all Video Player settings, HDR, live-preview, sort orders…), and your **EPG sources** are
  now included with the profiles & sources backup.
- **Aspect-ratio button in the player** — the player's zoom control now works in every mode (live,
  movies and series): **Fit · Fill/Crop · Stretch · Original · Force 16:9 · Force 4:3**. It resizes the
  video surface directly, so it works with the fast direct renderer too. (Fixes #4.)
- **D-pad is now strictly for navigation while watching live** — **D-pad Up/Down** move through the
  player controls (like Left/Right) instead of changing channels. Channel surfing stays on the
  dedicated **CH+ / CH−** keys. (No CH keys on your remote? Go back to the list to pick a channel.)
- **Picture-in-Picture for live TV** — the **PiP** button now works while watching a channel: dock it
  to a corner and keep browsing the app while it streams. **Selecting another channel updates the
  docked window in place**, and its expand button maximizes it again. (Fixes #6.)
- **Playlists show what's in them** — each row in **Settings → Playlists** now lists its **channel /
  movie / series counts** (e.g. *"40K channels · 100K movies · 30K series"*) instead of the old, stale
  "EPG not downloaded" note (EPG lives on its own screen now).

### 🛠️ Fixes

- **Favorites & history survive a re-sync** — content ids change every refresh, which used to orphan
  your data: the Favorites folder showed a count (e.g. *"(2)"*) but listed nothing. Favorites, watch
  history and resume positions now **re-attach to the refreshed content automatically** (and stale
  leftovers are cleaned up), so your starred channels/movies/series and recently-watched stay put —
  including across the refresh-on-startup.
- **Hiding a group now hides its channels everywhere** — hidden categories only dropped the rail
  folder before, so their channels still showed under **All Channels**, in search and in
  recently-watched (hiding the adult groups didn't actually hide the channels). Hidden groups' channels
  now drop out of those lists and counts too.
- **Plays more streams on weak boxes** — when a device's hardware decoder can't start a stream (some
  Fire TV Sticks reject otherwise-fine channels/VOD with *"playback error… unsupported format"*), the
  player now **retries that stream in software automatically** before showing an error — so you no
  longer have to turn off hardware decoding to watch those channels.
- **Movie backdrop no longer looks clipped** — the artwork in a movie's details pane now fills its
  banner cleanly instead of showing letterbox bars (or a thin sliver when only a poster was available).
  (Fixes #5.)
- **Simpler, crash-proof video** — the renderer picker (Smooth/Auto/**Quality**) is gone. The app now
  always uses the direct, *YouTube-style* decoder-to-surface path — the best quality (full native 4K,
  HDR handled by the panel) **and** the lightest on TV hardware. mpv's heavyweight GL renderer, which
  could hard-crash the whole app on some GPUs (e.g. an emulator's translated GL), is no longer a user
  option — it's kept only as the **automatic software-decode rescue**, and is skipped entirely on
  emulators (a clean "can't decode on this device" message shows instead).

## v2.1.0 — 2026-06-13

- **Channel up/down with the remote** — while watching a channel fullscreen, press **D-pad up/down**
  (or the **CH +/−** keys) to zap to the next/previous channel in the list you opened, with a brief
  "now watching" card — no need to go back to the category.
- **TV-friendly text entry** — focusing a text field (Add source, profile creation, dialogs) no
  longer pops the keyboard and traps you; it highlights like any control, **OK** opens the keyboard,
  **Back** closes it — so you can move straight to the Save button. (Fixes #3.)
- **Easier Fire TV install** — releases now also publish a stable `OwnTV.apk` so a fixed
  `…/releases/latest/download/OwnTV.apk` link always serves the newest signed build. Fire TV users
  can install via the **Downloader code `4308278`** (`aftv.news/4308278`); README has full
  sideload instructions.

## v2.0.1 — 2026-06-14

Playback polish and fixes from real-TV testing on top of v2.0.0.

- **Keep the screen awake while watching** — the TV screensaver no longer kicks in during playback
  (live, movies or series); it returns to normal when you pause or stop.
- **Renderer modes** — the renderer picker (Settings → Video Player) now offers **Smooth** (default —
  the direct, TV-optimized path), **Auto** (picks per device), and **Quality** (the full mpv GL
  renderer — heavier on weak TVs). Each option shows a one-line hint.
- **Recovers from a busy decoder** — a stream that doesn't start (e.g. the hardware decoder is still
  busy right after a TV cold-boot) is now retried automatically a few times before any error shows,
  instead of getting stuck. A transient hiccup no longer drops you to the slower renderer for the
  rest of the session.
- **Smoother subtitles, quieter logs** — the app-drawn subtitle overlay is fed more efficiently
  (no more constant background polling).

## v2.0.0 — 2026-06-13

This update delivers the complete, long-term vision for the app. I’ve been working on this feature set for a long time! My original goal was to launch with everything ready, but I decided to get the core IPTV features into your hands early so we could catch and fix any bugs first. Now, the full roadmap is finally here. This update brings you content customization, a smarter guide, resume & complete backup, in-app updates, custom accent colors, and a top-to-bottom D-pad navigation overhaul, plus all the bug fixes from the last update.

### ✨ New features

- **Playlist-order sorting** — sync now preserves your provider's original order (channels, movies,
  series, and category/group order). Each section (Live TV / Movies / Series) has a sort chip next to
  the search bar to toggle **Playlist/Provider order ↔ A–Z**, remembered per section. Live TV defaults
  to playlist order. *(Re-sync a source once to pick up the stored order.)*
- **Full category names** — the category rail expands when focused (like the sidebar) and shows full
  names; Favorites/History show icon + label.
- **Content customization (per profile, survives re-syncs)**
  - Hide, rename, and reorder **categories** in Live TV / Movies / Series (Settings → Customize).
  - Hide and rename **channels** straight from the Live preview pane.
  - Hidden-channels list (top of Settings → Customize) to unhide.
  - Hidden channels disappear everywhere: lists, folders, favorites, section & global search,
    recently watched, and the EPG guide.
- **Custom EPG URL per source** — for **Xtream and M3U**; your own XMLTV link overrides the defaults
  (Xtream `xmltv.php` / M3U `url-tvg`).
- **Tune from the Guide** — OK on a channel name tunes straight to it; programme details have a
  **Watch channel** button.
- **Guide search** — a search bar in the Guide filters channels across the *whole* guide (not just
  the visible rows).
- **Guide lists every channel** — rows load their programmes lazily as they scroll into view, so the
  guide shows your full lineup (no more 300-channel cap) with flat memory use.
- **Resume, your way** — replaying a movie/episode with a saved position now shows a small
  *"Resume at 23:45?"* prompt (Resume / Start over). A new **Resume playback** setting in Video Player
  settings picks the behavior: **Always resume · Ask to resume (default) · Never resume**.
- **In-app updates** — OwnTV updates itself straight from GitHub Releases: automatic check shortly
  after launch (toggleable via **Settings → Check updates on startup**), or manually via
  **Settings → Check for updates**. The startup check shows a small **top-right status card**
  ("Checking… / You're up to date", auto-hides) that stays with *Update now / Later* when a release
  is newer; the manual dialog shows the **full changelog**. Updating downloads the APK with progress
  and hands it to the system installer — no storage permission needed (the APK stays in app-private
  storage).
- **Custom accent colors** — the accent picker grew from 5 presets into a full **palette + hex code**
  input (e.g. `#52DBC8`); the whole Material theme is generated from your color.
- **Simpler Settings** — the Personalization sub-menu was dissolved: **Theme** (picker), **Accent
  color** and **UI Zoom** now live directly under Appearance (avatars are edited per profile in
  Profiles).
- **Selective backup & restore** — exporting asks *what* to include (profiles & sources,
  customizations, favorites, history, resume positions — or everything), and restoring shows the
  file's contents and lets you pick which parts to apply.
- **Restore on first launch** — setup now starts with a choice: create a new profile, or **restore
  everything from a backup file** (profiles included) without creating a throwaway profile first.
- **TV-style search bars** — focusing a search bar no longer opens the keyboard; it highlights like
  any control and the keyboard opens on **OK** (applies to Live/Movies/Series, the Guide and global
  Search).
- **About screen** — Settings gained a proper About dialog (version, license, author, project link);
  the old "Star on GitHub" / "Report a bug" browser links were removed (TV browsers are no place to
  send people).
- **EPG status** — the Guide shows *"Guide loaded: N channels · M programmes"*; each source row in
  Settings shows its EPG state (✓ + count, or "not downloaded").
- **Complete backup** — Backup & Restore now covers *everything*: profiles, playlists/sources,
  customizations, **favorites, watch history, and resume positions**. Favorites/history/resume
  re-attach automatically once the restored sources finish syncing (episode data attaches when you
  open the show).

### 🛠️ Fixes & stability

- **Runs properly on real TVs** — a top-to-bottom playback overhaul for TV-class hardware:
  - **Direct-to-display rendering**: on TV devices the hardware decoder now writes frames straight
    to the screen (the same zero-copy pipeline YouTube/Netflix use) — smooth 4K HDR with the TV's
    own native HDR handling, faster channel starts, and a far lighter memory footprint. Text
    subtitles are drawn by the app Netflix-style; a **Renderer** setting (Auto / Quality) can force
    mpv's full GL renderer (complete ASS/PGS subtitle styling + zoom modes) on devices that can
    afford it, and the app falls back to it automatically where direct rendering isn't available.
  - The player's memory scales to the device (the old emulator-tuned 256 MB stream buffer
    OOM-killed budget 4K TVs): lean buffers and cheaper framebuffers on low-RAM devices.
  - A **decode watchdog** stops playback with a clear message if a 4K/8K stream would fall back to
    software decoding (which overloads TV chips).
  - The image cache is capped, going to the background releases the stream immediately, and the
    app sheds caches when the system signals memory pressure instead of getting killed.
- **No more freezes (ANRs)** — all player commands run off the UI thread; a stalling stream can no
  longer lock up the remote. Fast preview-scrolling coalesces loads (only the channel you land on is
  opened).
- **Blank player fixed** — preview → fullscreen now **reuses the running stream** instead of
  reconnecting (no overlapping connections, which tripped strict 1-connection providers with
  HTTP 509). The transition is seamless now, too.
- **Live-drop recovery** — temporary provider errors (e.g. connection-limit responses right after a
  channel switch) are now retried at the network layer and usually ride over invisibly; if a live
  stream still dies, the player shows the buffering spinner and auto-reconnects, and only then a
  proper error + Retry — never a silent black screen.
- **Guide fixes** — the grid now picks only channels that actually have programmes (was scanning the
  first 300 by number) with case-insensitive EPG-id matching (fixed "guide loaded but empty"); Back
  in the Guide no longer blocks exiting the app.
- **Episode resume actually works now** — resume positions for series episodes were read on play but
  never saved; episodes now save progress every 10s like movies (and track prev/next in the queue).
- **Crash fixed** when hiding a live channel (Paging re-collection).
- **Profile PIN locks can now be removed** — the profile editor gained a *Remove PIN lock* toggle
  (previously a blank PIN field just kept the old PIN forever).
- **Restoring a backup keeps you in Backup & Restore** — it no longer bounced the app back to the
  Settings menu mid-restore (the profile swap briefly emptied the profile list, which reset the UI).
- **Category rail performance** — virtualized list + overlay expansion: buttery smooth with hundreds
  of categories (the channel grid is no longer re-laid-out during the animation).
- **Layout fixes** — the Movies download button no longer stretches; preview-pane buttons reflowed;
  the sort chip matches the search bar height.
- **Focus fixes** — rename dialogs focus their text field; the source edit form focuses the Name
  field; Settings → Sources restores focus after add / edit / re-sync / failed import.
- **D-pad navigation fixed everywhere** — moving between panels no longer lands on whatever happens
  to be horizontally aligned: entering the category rail always lands on the **selected folder**,
  entering the sidebar lands on the **current section**, entering a content pane lands on the
  **last-focused (or first) item — never the search bar**, every Settings sub-screen opens on its
  first control, and closing any dialog returns focus to the row that opened it. Returning from
  playback puts focus back on the **exact item you played** — the channel row in the Guide, the
  episode in a show, the poster in Movies/Series, the row in Downloads.

---

## v1.0.0 — First public release

Native Android TV IPTV **player** (bring your own M3U / Xtream sources):

- Live TV, Movies, Series with folder rail, favorites, history, and per-folder + global search
- Full **EPG guide** (time × channel grid) + now/next in the Live preview
- **libmpv (FFmpeg)** playback — plays nearly anything, full audio/subtitle track support, custom TV
  HUD, mini-player/PiP, HDR passthrough
- Multiple **profiles** with PIN lock & kids flag; sources shareable between profiles
- Offline **downloads** for movies & episodes
- **Backup & Restore** (profiles + sources), per-source User-Agent, refresh-on-startup,
  default source
- Material 3 design (AMOLED dark / light), accent colors, UI zoom, avatars
- Scales to huge playlists (tested ~64k channels / ~169k movies)
