# docs/LIVE-GUIDE-V2.md — Live TV + Guide (v2) (Stand: 2026-10-16, v2.1.3)

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

- Öffnen, Kanalwechsel und OK zeigen `LiveInfoBanner` für `INFO_BANNER_MS` (10 s seit v2.1.3; Back blendet ihn aus); OK bei
  sichtbarem Banner öffnet die Bedienleiste.
- Back schliesst den Player und landet auf der Guide-Zeile des laufenden Kanals.
- ◀ ▶ halten (Kanal mit Archiv): Scrubben über die Zeitleiste. Kurzer Druck wie bisher
  (Kanalliste / Verlauf, bzw. Schritt bei "Links/Rechts spult Live-TV").

## v2.0.2

- Guide-Video: Die Shell zeichnet das Vorschaubild mit ihrer einen gehoisteten Player-Surface an der
  Position der Video-Box (`EpgScreen(onVideoBounds)` → `guideVideoBounds`), dieselbe Surface wie im
  Vollbild. Back aus dem Vollbild verkleinert nur, statt Surface neu zu bauen (auf vielen Boxen
  Decoder-Reset = sah wie Neuladen aus). Nicht bei offener Kategorien-Leiste oder schwebender Rail
  (Surface läge darüber); dann zeigt die Box das Logo.
- Halten ◀ ▶ im Player öffnet nie die Listen; ohne Archiv und ohne „Live-TV anhalten und
  zurückspulen" erscheint ein Hinweis (`sport_player_no_rewind`).
- Kanalspalte 520/1920, Name zweizeilig (fokussiert: Marquee). Synopsis 23 mpx, Rest der Höhe.
- Back aus Settings → Live TV, wenn Settings von Live TV aus geöffnet wurde; Guide merkt sich
  `EpgViewModel.lastFocusedChannelId`.
- `MainSection.DVR` (Rail zwischen Live TV und Sport): `features/recordings/DvrScreen.kt`, fertige
  Aufnahmen, Standbild via `DvrThumbnails` (MediaMetadataRetriever, Cache `dvr_thumbs/`), Sortierung
  Datum/A–Z, OK spielt, OK halten/☰ = Abspielen/Löschen.

## v2.1.0

- Vollbild <-> Guide: Live TV bleibt unter dem Vollbild-Player komponiert (`OwnTVShell`: Browse-Box mit
  `alpha 0` + `onEnter = cancelFocusChange()` solange FULLSCREEN). Back baut den Guide nicht neu, lädt nicht
  neu, scrollt nicht neu; die gehoistete Surface wechselt nur ihre Größe.
- Navigation: ◀ auf "jetzt" schiebt die Kategorien links heraus (Guide rückt per `offset` nach rechts,
  `panelInset` = Platz der Rail-Kapsel), die Shell zeigt die Rail als Kapsel daneben (`guidePanelOpen`).
  ◀ in den Kategorien öffnet die Rail ohne Scrim, Kategorien bleiben (`sheetToRail`); ▶ aus der Rail
  fokussiert die Kategorien (Entry-Hook), ▶ aus den Kategorien den Guide. Auf dem Phone bleibt das Sheet.
- Entry-Hook: noch nicht gelegte Zeilen → `pendingEntry`, Fokus kommt nach, nie auf den Tagesfilter.
- Reload: `EpgViewModel.load(background = true)` aus dem Guide-Daten-Watcher leert nie einen sichtbaren
  Guide; `EpgScreen` mappt `focusedRow` nach jedem Reload über `lastFocusedChannelId` und holt den Fokus
  zurück, wenn der Reload ihn genommen hat (< 1,5 s).
- Start-Sync: `PlaylistRefresh.DEFAULT`/`EpgRefresh.DEFAULT` = STARTUP für neue Quellen; einmalig
  (`startup_sync_defaulted`) werden Quellen auf Off auf "Beim Start" gestellt (`AutoRefresh`).
- Sport: Vorschau erst nach OK (`previewId` lokal in `FixtureDetail`), zweites OK = Vollbild.
- Look nach DPTV Recorder (grün): Kopf mit Suche + Tages-Chips (`GuideDayChips`), Zeile "CHANNEL" über
  der Kanalspalte, Zellen mit 1-px-Linie, laufende Sendung mit Akzent-Rand + rotem LIVE-Tag, Zeit in
  IBM Plex Mono ("1:00 pm · 90m"), Kanal-Zelle mit Logo-Kachel und "CH n". Fonts: Barlow Condensed,
  IBM Plex Mono (OFL, `extras/font-licenses/`). Tags LIVE / Recording / Catch-up im Info-Block (`GuideTag`).
- Logo: `tools/brand/make_dptv_brand.py` erzeugt Icon (Recorder-Kachel + grünes Play) und Wordmark
  (Play-Glyph + DPTV PLAYER, Barlow Condensed).

## v2.1.1

- Raster: laufende Sendung ohne LIVE-Tag und Akzent-Rand; LIVE nur im Info-Block neben dem Video.
- ◀ aus den Kategorien öffnet die Rail immer mit Namen (`guideWide`: Größe Compact → Navigation-Breite bzw.
  Normal, auch bei Docked); ▶ schließt sie wieder auf die Kapsel, ▶ nochmal die Kategorien.
- Auto frame rate: die Guide-Box behält die im Vollbild gewählte Bildrate (`autoFrameRate = (isFull ||
  guideDock) && …`), statt den Anzeigemodus bei jedem Wechsel zweimal umzuschalten (das war die Pause).
- Start: neuer `StartupMode.GUIDE` (Standard; einmalig `startup_guide_defaulted`: Home → Guide) öffnet Live TV
  auf `lastWatchedLiveChannel()` mit laufender Vorschau.
- Leerlauf: 30 s ohne Taste/Touch im Guide (Vorschau aktiv, kein Dialog-Fenster) → Vollbild auf dem Kanal im
  Video (`GUIDE_IDLE_FULLSCREEN_MS`, `lastInputAt` in `OwnTVShell`).

## v2.1.2

- Die eigentliche Pause Guide <-> Vollbild: die stumme Vorschau hat die Audiospur abgewählt
  (`setTrackTypeDisabled(AUDIO)`), Vollbild wählte sie wieder an — jede Änderung ist ein Track-Wechsel,
  ExoPlayer setzt dabei die Renderer zurück. `LivePreviewEngine.keepAudioTrackWhileMuted` (für die eine
  Vorschau-/Live-Engine in `PlayerModule`) stummt nur per Lautstärke; Passthrough-Formate (AC3, E-AC3, DTS,
  TrueHD, AC-4), bei denen die Lautstärke nicht greift, werden weiter abgewählt. Multiview-Kacheln unverändert.
- Zweite Ursache (auch mit Vorschau-Ton): OK setzte FULLSCREEN sofort, `liveOnExo` kam erst einen Frame
  später an — für diesen Frame zeigte die Shell mpvs Surface, ExoPlayers wurde zerstört und neu gebaut.
  `exoOnScreen` liest zusätzlich `liveVm.liveOnExo.value` direkt.

## v2.1.3

- Kanalliste: ▼ auf dem letzten Kanal springt zum ersten.
- Guide: Last-channel- und Playlist-Pille oben ausgeblendet (`StageStatusCluster(showPills = false)` auf LIVE_TV).
- Fokussierte Zelle: normale Füllung + 3,5 px Akzent-Rand statt voller Akzent-Füllung.
- Back im Guide = Vollbild auf dem Kanal im Video; ◀ auf der Rail (`onLeftEdge`) = Beenden-Dialog.
- Kategorie gewählt: Fokus geht vor dem Schließen auf den Tages-Chip, `focusGrid` versucht es bis zu
  2 s, bis die neue Liste da ist (vorher fiel der Fokus auf die Rail).
- Info-Banner 10 s (Öffnen, OK, Kanalwechsel); Back blendet ihn aus, ohne den Player zu verlassen.

## v2.1.4

- Vorschau → Vollbild: `LiveTuneController` merkt sich den Kanal der Exo-Vorschau (`exoChannelId`) und
  übernimmt per Kanal statt per exakter URL. Wechselt die Vorschau selbst auf die andere Adresse
  (.ts ⇄ .m3u8, erneuerter Link, direct_source), wird der Kanal bei OK/Back nicht mehr neu geöffnet.
- Guide-Info: der Titel hält rechts Abstand zur Uhr (`CLOCK_CLEARANCE`, `GuideInfoBlock(titleEndInset)`)
  und bricht auf zwei Zeilen um, statt unter die Uhr zu laufen.
- Sport: rechter Rand 64 statt 28 mpx (wie Uhr/Guide); Tabellenwerte rechtsbündig, 52 mpx breit.

## v2.1.5

- Lokales Timeshift an: die Vorschau spielt den Kanal schon aus der gespeicherten Kopie
  (`LiveTuneController.preview` → `openTimeshift`), OK befördert also denselben Stream. Vorher stoppte
  das Vollbild die direkte Vorschau und öffnete den Kanal über die Kopie neu (3–4 s). Kann der Kanal
  nicht gespeichert werden, bleibt der direkte Stream (`tsRefusedKey`).
- Kategorie wechseln: `EpgViewModel.refilter()` filtert die zuletzt gebaute, sortierte Liste neu statt
  alles neu zu laden (alle Kanäle, Matches, Sortierung, Zählung der Programmtabelle).
- Nach der Kategorie-Wahl wartet der Fokus auf die NEUE Liste (`categoryFocusFrom`); vorher bekam die
  erste Zeile der alten Liste den Fokus, verlor ihn beim Austausch, und ▼ lief im Menü.
