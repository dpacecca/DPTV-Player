# docs/BRANDING.md — Sportio aussen, OwnTV innen (Stand: 2026-10-13)

> Zweck: definiert, was umbenannt ist und was per Freeze technisch OwnTV bleibt.
> Beschluss mit User am 2026-10-13: kein Code-Rename, nur Doku.

## Umbenannt (user-sichtbar, erledigt)

- `applicationId = "tv.sportio.app"` (`app/build.gradle.kts:54`) — parallele
  Installation neben offiziellem OwnTV, eigene Daten/Provider/Launcher-Eintrag.
- `app_name = Sportio`, `brand_own = Sport`, `brand_tv = io`
  (`res/values/donottranslate.xml`); `sport_*`-Strings in `values/` + `values-de/`.
- Deep-Links `sportio://` (18x im Manifest), `CoreBuildInfo.releaseRepo =
  "SportioTV"` (`OwnTVApp.kt`), eigener Keystore.
- Palette: Core `SportioPalette` (Pitch #4FEE94 / PitchDeep #0E6B41,
  Dark-Page #060B07), `AccentColor.SPORTIO` (einziges Preset, alte lösen nach Grün
  auf), `AppIcon.SPORTIO` (einzige Activity, `sportio_banner`, `sportio_mark`),
  `owntv_*`-Assets (152 Dateien) + `telegram_qr.jpg` + `owntv_wordmark.png` gelöscht.
- About ohne Telegram/GitHub; Accent-/Icon-/Brand-Settingszeilen entfernt.

## Eingefroren (technisch OwnTV, nicht anfassen)

| Bereich | Beispiel | Warum Freeze |
|---|---|---|
| Kotlin-Pakete | `tv.own.owntv.*` (4189 Treffer) | ~alle Imports, Koin, Tests; Null Nutzerwert |
| `android.namespace` | `tv.own.owntv` (`app/build.gradle.kts:33`) | Manifest/R-Klassen |
| Core-Group | `tv.own.owntv:core` (`gradle/libs.versions.toml:86`) | Maven-Republish + Composite-Build |
| Klassen/Dateien | `OwnTVApp.kt`, `OwnTVShell.kt`, `OwnTVIcon.kt`, … (9 Dateien, 1713 Treffer) | Import-Kaskade, Test-Referenzen |
| DB | `OwnTVDatabase` | Room-Migration nötig |
| Backup/Daten | Endung `.own`, Dateiname `owntv-backup.own`, Ordner `OwnTV` | Backup-Kompat mit Upstream |
| `rootProject.name` | `OwnTV` (`settings.gradle.kts:50`) | Nur Kosmetik im Build-Log |

## Regel für neuen Code

- User-sichtbarer Text: `sport_*`-Keys in `strings.xml` (+ de), nie Hardcoded-Literale.
- Interne Namen: keine neuen `OwnTV*`-Klassen erfinden, bestehende nicht umbenennen.
- Brand-Referenz: `brand/BRAND_GUIDE_SPORTIO.md` + `brand/sportio.tokens.json`.
