# docs/RELEASE.md — Signing, Versionierung, Updates (Stand: 2026-10-15)

> Zweck: alles für signierte Releases und In-App-Updates an einem Ort.

## Signing

- Eigenen Keystore anlegen (z. B. `keystore/dptv-release.jks`, Alias `dptv`), nie committen
  (`.gitignore`: `*.jks`/`*.keystore`). Backup extern halten — ohne ihn keine Updates über
  bestehende Installationen.
- Lokale Credentials nur in `~/.gradle/gradle.properties` oder per Env
  (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
- Repo-Secrets für CI: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
  Ohne sie baut CI ein debug-signiertes APK.

## Versionierung

- CI injiziert `VERSION_CODE`/`VERSION_NAME` aus dem Git-Tag
  (`.github/workflows/android.yml`).
- Lokale Fallbacks: versionCode 99999 / versionName 99.99.99 — Debug-APKs sind
  damit immer „neuer" als Releases (kein Downgrade-Fehler, kein Update-Angebot).
  Kehrseite: ein Release installiert NICHT über einen Debug-Build — erst deinstallieren
  (Daten vorher als `.own` sichern).
- Core: liegt als Quellcode in `core-lib/` und wird per `includeBuild` mitgebaut. Kein Core-Pin,
  kein Core-Release, kein Maven-Publish nötig. `owntvCore` in `gradle/libs.versions.toml` ist nur
  ein Label (gleich `coreVersion` in `core-lib/build.gradle.kts` halten).

## Release-Ablauf

**Release-Button (Standard):** GitHub → Actions → *Android CI* → *Run workflow* → `release` =
`patch` / `minor` / `major`. Die Version wird automatisch aus dem neuesten `v*`-Tag hochgezählt
(`v1.0.0` → `v1.0.1` / `v1.1.0` / `v2.0.0`); der Release-Schritt legt den Tag selbst an, niemand
muss einen Tag pushen. Dauer: Tests ~2 Min, signierter Build ~20 Min.

- Release-Notes: der `## vX.Y.Z`-Abschnitt in `CHANGELOG.md`, falls vorhanden; sonst die
  Commit-Titel seit dem letzten Release.
- Ergebnis: `DPTV-Player-vX.Y.Z.apk` (arm) als GitHub-Release. Kein x86_64-APK mehr.
- Ein von Hand gepushter Tag `vX.Y.Z` funktioniert weiterhin genauso.
- versionCode = Major×10000 + Minor×100 + Patch, daher Minor/Patch < 100 (der Button bricht sonst ab).

## In-App-Updates

- `CoreBuildInfo.releaseRepo = "dpacecca/DPTV-Player-releases"` (`OwnTVApp.kt`),
  `updateCheckOnStart = true` (Core `SettingsRepository.kt`). Manuell: More → Check for updates.
- Der Updater fragt die GitHub-API ohne Login. Das Quell-Repo ist **privat** (404 ohne Login),
  daher veröffentlicht CI jedes Release zusätzlich im **öffentlichen** Repo
  `dpacecca/DPTV-Player-releases` (nur Releases, kein Code; braucht mindestens einen Commit,
  z. B. README, damit der Tag einen Branch hat).
- Repo-Secret `RELEASES_REPO_TOKEN`: Fine-grained Token, nur Repo `dpacecca/DPTV-Player-releases`,
  Permission *Contents: Read and write*. Fehlt es, überspringt CI die Kopie mit Warnung.
- Kopie nachholen / Token prüfen: Actions → *Mirror release* → Run workflow (Tag leer = neuestes
  Release). Schlägt mit klarer Meldung fehl, wenn das Secret fehlt oder keinen Zugriff hat.
- Installation per `PackageInstaller`-Session: Download, Paketname prüfen, installieren. Ab Android 12
  mit `USER_ACTION_NOT_REQUIRED` — ohne Rückfrage, sobald die App sich einmal selbst aktualisiert hat
  (vorher ist der Dateimanager "Installer of record" und das System fragt einmal). Beim allerersten
  In-App-Update fragt Android ausserdem einmal nach "Unbekannte Apps installieren" für DPTV-Player.
- Release-Artefakt: `DPTV-Player-vX.Y.Z.apk` (arm); der Updater nimmt jedes `.apk`-Asset.

## Externe Dienste

- libmpv (`tv.own.owntv:libmpv`) kommt aus dem öffentlichen Maven von Upstream-OwnTV
  (`https://ahxn00.github.io/OwnTV_Core/maven`).
- TMDB-Metadaten und OpenSubtitles laufen standardmässig über die Worker von Upstream-OwnTV
  (`*.xiannero.workers.dev`, siehe `TmdbProvider.kt` / `OpenSubtitlesClient.kt`). Eigene Worker-URL
  oder API-Key für OpenSubtitles lässt sich in den Einstellungen setzen.
