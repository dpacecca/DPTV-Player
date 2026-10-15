# docs/LIVE-GUIDE-V2.md — Live TV + Guide (v2) (Stand: 2026-10-15)

> Zweck: Verhalten und Tastenbelegung des zusammengelegten Live-TV/Guide-Screens und des Player-Banners ab v2.0.0.

## Screen

- `MainSection.LIVE_TV` zeigt immer `EpgScreen(liveMode = true)`. `MainSection.EPG` hat keinen
  Rail-Eintrag mehr und leitet auf `LIVE_TV` um (`OwnTVShell.kt`). `LiveScreen` (Listenansicht)
  wird nicht mehr gerendert, der Code bleibt.
- Oben: Video 528×297 mpx + `GuideInfoBlock` (Titel 2 Zeilen, Zeit + Fortschritt + Restminuten,
  Catch-up, Kanal + Stream-Chips "1080p"/"25 FPS"/"STEREO" aus `previewEngine.streamChips`, nur
  wenn dieser Kanal in der Vorschau läuft).
- Kopfzeile: Tag ▾, Sortierung, Suche, Auto-Match über der Kanalspalte; Lineal über dem Raster.
- Zeilen 80 mpx, Titel bis 2 Zeilen; Kanalspalte 400/1920 mit Nummer, Logo, Name (Marquee bei Fokus).
- Unter 600 dp Breite (Phone): Video volle Breite, Info darunter, Kanalspalte 136 dp.

## Tasten im Guide

| Taste | Wirkung |
|---|---|
| ▲ ▼ | Kanal, gleiche Uhrzeit |
| ▶ | nächste Sendung |
| ◀ | vorige Sendung bis "jetzt"; auf "jetzt" öffnet ◀ die Kategorien, dort ◀ die Rail (Settings) |
| ◀ halten | nicht auf "jetzt": zurück zu jetzt; auf "jetzt": in vergangene Sendungen (Back = zurück zu jetzt) |
| OK auf laufender Sendung | Kanal in die Vorschau; nochmal OK = Vollbild |
| OK auf vergangener Sendung | Catch-up ab Start (sonst Details) |
| OK auf künftiger Sendung | Details (Erinnern, Aufnehmen) |
| OK halten / ☰ | Details; bei begonnener Sendung steht Aufnehmen oben und hat den Fokus |

Die Vorschau folgt dem Fokus nicht mehr, nur OK.

## Player (live)

- Öffnen, Kanalwechsel und OK zeigen `LiveInfoBanner` für `INFO_BANNER_MS` (3 s); OK bei
  sichtbarem Banner öffnet die Bedienleiste.
- Back schliesst den Player und landet auf der Guide-Zeile des laufenden Kanals.
- ◀ ▶ halten (Kanal mit Archiv): Scrubben über die Zeitleiste. Kurzer Druck wie bisher
  (Kanalliste / Verlauf, bzw. Schritt bei "Links/Rechts spult Live-TV").
