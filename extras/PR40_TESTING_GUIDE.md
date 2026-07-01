# PR #40 Integration — My Testing Guide (Owner's Part)

This is the simple, step-by-step checklist of what **I** (the owner) need to test on a real TV device
after the PR #40 sync/EPG performance work was integrated. Everything else (code fixes, build, unit
tests, migration tests compiling) is already done. My job is only the on-device testing below.

**The app package is `tv.own.owntv`. All commands are for Windows PowerShell.**

> Golden rule for the whole guide: after the very first v3.2.0 install, **never uninstall the app
> again**. Upgrading must always be done with `adb install -r ...` so the database is kept.
> Uninstalling deletes the database and ruins the test.

---

## Test 1 — Upgrade from v3.2.0 (the most important test)

**Goal:** a real user on v3.2.0 updates the app → nothing crashes, nothing disappears.

### Step 1. Build and install the old v3.2.0 APK

```powershell
cd E:\local\AI\OwnTV
git worktree add ..\OwnTV-v320 82e336c195d2b8e91b6457512635018c43744950
cd ..\OwnTV-v320
.\gradlew :app:assembleDebug
adb uninstall tv.own.owntv
adb install -r "E:\local\AI\OwnTV-v320\app\build\outputs\apk\debug\app-debug.apk"
```

(The `adb uninstall` here is allowed **once** — it gives a clean start with a true v3.2.0 database.)

### Step 2. Create test data inside v3.2.0

Open the app on the TV and do all of this:

1. Finish the setup wizard, create profile **Primary**, add my **Xtream source**, let the sync finish.
2. In Settings, also add my **M3U source**.
3. Add **favorites**: 2–3 Live channels, 2 movies, 1 series.
4. Play a **movie** for ~2 minutes and exit (creates a resume point). Play one **series episode** for
   ~2 minutes. Open 2–3 Live channels briefly (creates history).
5. Settings → EPG → add and sync my **EPG source** (so the Guide has data).
6. Settings → Manage sources → turn ON **refresh on startup** for the Xtream source.
7. **Write down**: how many favorites are in each tab, and the movie's resume position.

### Step 3. Upgrade to the new build

```powershell
cd E:\local\AI\OwnTV
git branch --show-current        # must print: pr40-integration (or main after the merge)
.\gradlew :app:assembleDebug
adb logcat -c
adb install -r "E:\local\AI\OwnTV\app\build\outputs\apk\debug\app-debug.apk"
```

Now open the app from the TV launcher, wait for the home screen, then save the log:

```powershell
adb logcat -d > "$env:USERPROFILE\Desktop\owntv-first-launch.log"
```

### Step 4. Check for a migration crash

```powershell
Select-String -Path "$env:USERPROFILE\Desktop\owntv-first-launch.log" -Pattern "Migration didn't properly handle|A migration from .* was required|dropAllTables|FATAL EXCEPTION"
```

- **No output = good.**
- Any output = FAIL → stop testing and report the log file.

### Step 5. Check everything survived

- [ ] App opens normally, three times in a row.
- [ ] Profile **Primary** exists, both sources are in Settings → Manage sources.
- [ ] Favorites: same counts as written down in Step 2.7, in every tab.
- [ ] History shows the watched items; the movie resumes at the right position.
- [ ] The Guide/EPG still shows programme data without re-syncing.
- [ ] Database version check (optional but nice):

```powershell
adb exec-out run-as tv.own.owntv cat databases/owntv.db > owntv-pulled.db
sqlite3 owntv-pulled.db "PRAGMA user_version;"     # must print: 9
```

### Step 6. Resync must not duplicate anything

1. Settings → Manage sources → resync the Xtream source, let it finish.
2. Open a category I know — the channel/movie count must be the same, not doubled.
3. Check the log took the smart (incremental) path:

```powershell
adb logcat -d | Select-String "fresh=|dbSkipped"
```

I want to see `fresh=false` and `dbSkipped` numbers greater than 0.

---

## Test 2 — Priority sync ("Live first, rest in background")

**Goal:** the new staged sync marks the source as properly synced at the end (this was one of the bugs).

1. Clean start (this test is allowed to wipe): `adb uninstall tv.own.owntv`, then install the new APK.
2. In the wizard, add the Xtream source and pick **Live only** as the priority.
3. Live finishes fast and I land in the app. Movies/series continue in the background.
4. Watch the background worker finish:

```powershell
adb logcat CatalogSyncWorker:* *:S
```

- [ ] I must see a line with `reason=add_remainder` and then:
      **`Staged initial sync complete — markSynced sourceId=...`** ← this line is the fix working.
5. Verify in the database:

```powershell
adb exec-out run-as tv.own.owntv cat databases/owntv.db > f.db
sqlite3 f.db "SELECT id, name, lastSyncAt FROM sources;"
```

- [ ] `lastSyncAt` must NOT be empty/NULL.
6. Force-stop the app (`adb shell am force-stop tv.own.owntv`), open it again, add one favorite,
   then resync the source from Settings.
- [ ] The favorite is still there after the resync.
- [ ] Log shows `fresh=false` (not `fresh=true`).

---

## Test 3 — Failed sync must not hide my favorites

**Goal:** if a sync dies halfway (bad Wi-Fi), favorites/history/resume stay visible.

1. On the upgraded device from Test 1, confirm favorites/history are visible.
2. Start a manual resync of the **M3U** source. While it is running, kill the network:

```powershell
adb shell svc wifi disable
# Fire TV alternative if the above does nothing:
adb shell cmd wifi set-wifi-enabled disabled
```

3. The sync fails with an error — that's expected.
- [ ] Now check every favorites tab and History: **nothing may have disappeared.**
4. Check the log:

```powershell
adb logcat -d | Select-String "userData relink"
```

- [ ] There must be a relink line even for the failed sync, with `purge=false`.
5. Turn Wi-Fi back on (`adb shell svc wifi enable`), resync again.
- [ ] Everything still correct, and the successful sync logs `purge=true`.
6. Repeat steps 2–5 once with the **Xtream** source.

---

## Test 4 — Big EPG must not eat all the memory

**Goal:** the new incremental EPG sync stays memory-safe on a TV box.

1. Settings → EPG → sync my biggest guide. Let it finish. Then sync it a **second** time
   (the second run is the one that used to build a giant map in memory).
2. While the second run is going, watch memory (24 samples, one every 5 seconds):

```powershell
1..24 | ForEach-Object { adb shell dumpsys meminfo tv.own.owntv | Select-String "TOTAL PSS|Java Heap"; Start-Sleep 5 }
```

- [ ] Java Heap stays roughly flat (tens of MB). It must NOT climb by hundreds of MB.
- [ ] If the guide is enormous I may see this in the log — that is **fine**, it's the safety valve:

```powershell
adb logcat -d | Select-String "EPG hash tracker cap"
```

3. - [ ] The sync completes, and the Guide opens and scrolls normally afterwards.

---

## Test 5 — Sleep / reboot during background sync (Fire TV style)

**Goal:** background work survives sleep and reboot without duplicating content.

1. Fresh-add a source with Live priority (like Test 2), and right after landing in the app,
   put the device to sleep: `adb shell input keyevent KEYCODE_SLEEP`
   (wake it later with `KEYCODE_WAKEUP`).
2. Wake it, watch `adb logcat CatalogSyncWorker:* *:S` — the remainder sync should resume or restart
   and end with the markSynced line.
3. Do the same once more, but instead of sleep, reboot: `adb reboot`.
- [ ] After both: no duplicated movies/series (spot-check one category count), favorites intact.

---

## Test 6 — Playback quick pass (nothing regressed)

Just play things and tick boxes:

- [ ] Live TV (ExoPlayer): zap 5–10 channels, picture+sound come fast, no black screen with audio.
- [ ] A compatibility-mode (mpv) channel plays fine.
- [ ] Reconnect: while watching Live, turn Wi-Fi off for ~30 seconds, then on — the player must keep
      retrying and recover (it must NOT say "Lost connection" too early).
- [ ] Live preview pane works when focusing channels in the list.
- [ ] Catch-up rewind into the archive and back to live.
- [ ] A movie plays, seeking works, resume works.
- [ ] A file with image subtitles (PGS/VOBSUB) still shows the subtitles.
- [ ] Series autoplay jumps to the next episode (also across seasons).
- [ ] If I use the proxy: streams and sync still go through it.
- [ ] Backup: create + restore a settings backup once.

---

## Cleanup when everything is done

```powershell
cd E:\local\AI\OwnTV
git worktree remove ..\OwnTV-v320
```

---

## Draft reply for PR #40

> Hey, thanks a lot for this PR — this is genuinely great work! 🎉 The WorkManager-based background
> sync, the incremental hash-based upserts, the HTTP retry/progress plumbing and the priority
> ("Live first") setup flow are all things OwnTV really needed, and the code quality made it easy to
> review. **The PR is accepted** and has been merged through a local integration branch, with a few
> adjustments I had to make on top before it could ship safely. Sharing them here so you know what
> changed and why:
>
> 1. **Database migrations were renumbered.** The PR reused schema versions 5–7 with new meanings,
>    but version 7 (the `content_order` table for the manual-reorder feature) already exists on
>    devices running current main builds — those devices would have failed Room's schema validation
>    and crashed (or been wiped by the destructive fallback) on upgrade. The contentHash columns +
>    indexes now live in a new **v8** migration and the EPG contentHash/natural-key in **v9**, on top
>    of main's untouched v1–v7 chain, so both v3.2.0 users and dev builds upgrade cleanly. I also
>    added migration tests for the two real-world paths (public v3 → v9 and main v7 → v9).
> 2. **Staged priority sync never set `lastSyncAt`.** `markSynced` only ran for a *full* Xtream sync,
>    but the priority flow is two partial syncs (foreground priority + background remainder), so the
>    source stayed "never synced" forever — every later sync took the fresh-import fast path, which
>    REPLACE-inserts (row-id churn breaking favorites) and never prunes removed channels. The
>    remainder worker now carries a `completesInitialSync` flag and marks the source synced when it
>    finishes, since priority + remainder always cover all content types together.
> 3. **User-data relink only ran on success, and the `purge` flag was ignored.** A sync that failed
>    partway can still have rewritten content ids (M3U is clear-then-insert), which made
>    favorites/history/resume silently invisible after a failed sync. Relink now runs after *every*
>    sync attempt; purging orphans is only allowed after a fully successful, full-content sync. Small
>    catch: `relinkAfterSync` accepted a `purge` parameter but never actually checked it — fixed.
> 4. **The incremental EPG sync loaded every programme hash for a source into one map.** With big
>    guides (millions of rows) that's hundreds of MB of heap — an OOM on low-RAM TV boxes. It's now a
>    per-channel, lazily-loaded tracker with a ~100k-entry cap; channels past the cap fall back to
>    write-through, which stays correct thanks to your natural-key unique index + REPLACE.
>
> None of this takes away from the PR — the architecture is exactly right, these were edge cases
> around upgrade paths and failure modes. Thanks again for the contribution, and looking forward to
> the next one! 🙏
