# docs/RELEASE.md — Signing, Versionierung, Updates (Stand: 2026-10-14)

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
- Ergebnis: `DPTV-Player-vX.Y.Z.apk` und `DPTV-Player-x86_64-vX.Y.Z.apk` als GitHub-Release.
- Ein von Hand gepushter Tag `vX.Y.Z` funktioniert weiterhin genauso.
- versionCode = Major×10000 + Minor×100 + Patch, daher Minor/Patch < 100 (der Button bricht sonst ab).

## In-App-Updates

- `CoreBuildInfo.releaseRepo = "dpacecca/DPTV-Player"` (`OwnTVApp.kt`),
  `updateCheckOnStart = true` (Core `SettingsRepository.kt`).
- Der Updater fragt die GitHub-API ohne Login. Solange das Repo **privat** ist, findet er keine
  Releases (kein Update-Angebot, keine Fehlermeldung an den User).
- Release-Artefakt: `DPTV-Player-vX.Y.Z.apk` (arm); der Updater nimmt jedes `.apk`-Asset.

## Externe Dienste

- libmpv (`tv.own.owntv:libmpv`) kommt aus dem öffentlichen Maven von Upstream-OwnTV
  (`https://ahxn00.github.io/OwnTV_Core/maven`).
- TMDB-Metadaten und OpenSubtitles laufen standardmässig über die Worker von Upstream-OwnTV
  (`*.xiannero.workers.dev`, siehe `TmdbProvider.kt` / `OpenSubtitlesClient.kt`). Eigene Worker-URL
  oder API-Key für OpenSubtitles lässt sich in den Einstellungen setzen.
