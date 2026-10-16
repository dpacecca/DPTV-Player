# docs/BRANDING.md — DPTV-Player aussen, OwnTV innen (Stand: 2026-10-16)

> Zweck: definiert, was umbenannt ist und was per Freeze technisch OwnTV bleibt.

## Umbenannt (user-sichtbar, erledigt)

- `applicationId = "tv.d## Umbenannt (user-sichtbar, erledigt)

- `applicationId = "tv.dptv.player"` (`app/build.gradle.kts`) — eigene App, eigene Daten.
- `app_name = DPTV-Player` (`res/values/donottranslate.xml`); `sport_*`-Strings in `values/` + `values-de/`.
- Deep-Links `dptv://` (Manifest), `CoreBuildInfo.releaseRepo = "dpacecca/DPTV-Player"` (`OwnTVApp.kt`).
- Palette: Core `DptvPalette` (Grün #4FEE94 / #0E6B41, Dark-Page #060B07), `AccentColor.DPTV`,
  `AppIcon.DPTV`.
- Brand-Assets (`dptv_mark*`, `dptv_notification`, `dptv_wordmark*`, `dptv_banner`, README-Logos)
  werden von `tools/brand/make_dptv_brand.py` erzeugt — Script ändern, nicht die Dateien.
  Seit v2.0.3 im Stil von DPTV Recorder in Grün: Icon = Recorder-Kachel mit grünem Play-Dreieck,
  Logo = Play-Glyph + "DPTV" + "PLAYER" (Barlow Condensed SemiBold).

ispiel | Warum Freeze |
|---|---|---|
| Kotlin-Pakete | `tv.own.owntv.*` (4189 Treffer) | ~alle Imports, Koin, Tests; Null Nutzerwert |
| `android.namespace` | `tv.own.owntv` (`app/build.gradle.kts:33`) | Manifest/R-Klassen |
| Core-Group | `tv.own.owntv:core` (`gradle/libs.versions.toml:86`) | Maven-Republish + Composite-Build |
| Klassen/Dateien | `OwnTVApp.kt`, `OwnTVShell.kt`, `OwnTVIcon.kt`, … (9 Dateien, 1713 Treffer) | Import-Kaskade, Test-Referenzen |
| DB | `OwnTVDatabase` | Room-Migration nötig |
| Backup/Daten | Endung `.own`, Dateiname `owntv-backup.own`, Ordner `OwnTV` | Backup-Kompat mit Upstream |

## Regel für neuen Code

- User-sichtbarer Text: `sport_*`-Keys in `strings.xml` (+ de), nie Hardcoded-Literale.
- Interne Namen: keine neuen `OwnTV*`-Klassen erfinden, bestehende nicht umbenennen.
- Brand-Assets: `tools/brand/make_dptv_brand.py`.
