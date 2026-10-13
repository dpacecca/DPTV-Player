# Sportio — Brand Guide

**Version 1.0 · Stand 2026-10-12**
Produkt: Sportio (Android-TV-Sports-Edition, Fork von OwnTV) · Paket-ID `tv.sportio.app`

![Sportio Logo-System](./assets/png/logo-board.png)

Dieses Dokument ist die verbindliche Referenz für alles, was sichtbar ist: Farbe, Schrift,
Text, Ton, Layout, Motion, Icons und Barrierefreiheit. Es ist aus dem bestehenden Code
abgeleitet (OwnTV-Palette, Stage-Designsystem, Sports-Hub) und um einen vollständigen
Grünton erweitert.

| | |
|---|---|
| **Einstieg / Index** | [`README.md`](./README.md) |
| **Maschinenlesbare Tokens** | [`sportio.tokens.json`](./sportio.tokens.json) |
| **Kotlin-Drop-in** | [`SportioPalette.kt`](./SportioPalette.kt) |
| **Logo-Assets** | [`assets/`](./assets/) — SVG, PNG, VectorDrawable |
| **Werkzeuge** | [`tools/brand/`](../../tools/brand/) — Generator + drei Konsistenzprüfungen |

> **Alle Farbangaben in diesem Guide sind geprüft, nicht geschätzt.** Jede Textpaarung
> erreicht mindestens WCAG AA (4,5:1), jede Nicht-Text-Paarung mindestens 3:1 — in beiden
> Themes und gegen die Fläche, auf der der Wert tatsächlich landet. Nachrechnen:
> `tools/brand/check_contrast.py`.

---

## Inhalt

1. [Marke](#1-marke)
2. [Logo & Wortmarke](#2-logo--wortmarke)
3. [Ton & Stimme](#3-ton--stimme)
4. [Text & Redaktion](#4-text--redaktion)
5. [Farbe](#5-farbe)
6. [Typografie](#6-typografie)
7. [Layout, Raum & Form](#7-layout-raum--form)
8. [Motion](#8-motion)
9. [Icons](#9-icons)
10. [Komponenten](#10-komponenten)
11. [Sport-Spezifika](#11-sport-spezifika)
12. [Barrierefreiheit](#12-barrierefreiheit)
13. [Implementierung in der App](#13-implementierung-in-der-app)
14. [Checklisten](#14-checklisten)

**Anhänge:** [A Farbreferenz](#anhang-a--farbreferenz-auf-einen-blick) ·
[B Logo-Textbausteine](#anhang-b--logo-textbausteine) ·
[C Glossar](#anhang-c--glossar)

---

## Kurzfassung — die zehn Regeln

Wer nicht den ganzen Guide liest, braucht diese zehn:

1. **Grün ist System.** `#4FEE94` auf dunkel, `#0E6B41` auf hell. Nichts anderes ist Marke.
2. **Rot ist reserviert.** Rot heißt *jetzt live* — nie Dekoration, nie Kategorie.
3. **Farbe ist nie allein.** Jede Information hat zwei Kanäle, minimum (§12.1).
4. **`Sport` grün, `io` neutral.** Die Wortmarke, in Plus Jakarta Sans ExtraBold 800, Tracking −1,8 %.
5. **Kein Text unter 7,8 sp.** Der Fernseher ist 3 Meter weg.
6. **Buttons: 1–3 Wörter, Lead-Verb, Sentence case.** Nie `OK`, nie `!`.
7. **Leer ist ein gültiger Zustand.** Und sagt, warum, und was der Nutzer tun kann.
8. **Fokus ist der wichtigste Zustand.** Er ändert Füllung *und* Text, nicht nur den Ring.
9. **Eine Änderung an einer Farbe ändert Guide + Kotlin + Tokens.** Sonst greift der Audit.
10. **Alles überprüfen.** Ein Wert ohne Codepunkt ist ein toter Token.

---

## 1. Marke

### 1.1 Was Sportio ist

Sportio ist ein **TV-Sports-Player für den Fernseher**. Kein Stream-Anbieter, kein Abo,
keine Inhalte — der Nutzer bringt seine eigenen Quellen mit. Die App verbindet zwei Dinge,
die vorher getrennt waren: **den linearen TV-Kanal** und **den Sport-Hub** (Spiele, Ergebnisse,
Tabellen, Kanal-Mapping auf eigene Sender).

| | |
|---|---|
| **Produkt** | Sportio — Android TV App (Compose for TV) |
| **Zielnutzer** | Sportfans mit eigenem IPTV/Zugang, die am Fernseher Live-TV schauen |
| **Kernnutzen** | „Ich sehe das Spiel, und ich finde den Sender, der es zeigt." |
| **Markenkern** | **Am Ball bleiben.** Live, ohne Umwege. |
| **Positionierung** | Der schnelle Weg vom Spiel zum Sender — alles andere ist Nebensache. |
| **Ton** | Kompakt, sachlich, ruhig. Fernsehbarbeit vor Witz. |

### 1.2 Grundsätze (ranked)

1. **Ein Bildschirm, eine Aussage.** Kein Screen versucht, mehr zu sein, als er ist.
2. **Grün ist System, nicht Dekoration.** Wenn etwas grün ist, ist es * Sportio oder es ist live*.
3. **Vom Spiel zum Sender in zwei Klicks.** Der Hub ist keine Browser-Seite, er ist eine Kanal-Liste.
4. **Fernsehbarbeit vor Schönheit.** 10-Foot-UI: groß, kontrastreich, kein Text unter 7,8 sp.
5. **Nie lügen.** Kein „3 Spiele" wenn keins laufen. Leer ist ein gültiger Zustand.

### 1.3 Was wir *nicht* tun

- ❌ Keine Ausrufe-Interpunktion in UI-Text (`!`, `!!!`) — die App spricht, sie schreit nicht.
- ❌ Kein „Wir sind excited, dass…" — Job ist erledigt, weiter.
- ❌ Keine Sammel-Buffs, Fan-Artikel, „Top 10 Spieler der Woche" — Sportio ist Werkzeug.
- ❌ Keine Farbe als alleiniger Informationsträger (§12).
- ❌ Kein Verspott von Teams oder Vereinen — auch nicht von Verlierern.

### 1.4 Abgrenzung zu OwnTV

Sportio ist ein **Fork**, kein Rebrand: technisch, rechtlich und strukturell identisch
(GPLv3, gleiche Core-Bibliothek). Unterschieden sind:

| | OwnTV (Upstream) | Sportio |
|---|---|---|
| Fokus | Medien-Player allgemein | Sport + TV |
| Navigation | Suche, Home, Live, Guide, Movies, Series, Downloads, More | …ohne Movies/Series/Downloads |
| Quellen-Sync | M3U/Xtream/Stalker inkl. VOD | **nur Live** (schnellerer Sync) |
| Sport-Hub | – | **16 Ligen, Live/Heute/Demnächst/Ergebnisse, Tabellen, Kanal-Mapping** |
| Akzent | Petrol `#52DBC8` (Hue 172) | **Grün `#4FEE94` / `#0E6B41`** (Hue 146) |
| Update-Kanal | GitHub Releases upstream | keiner (kein eigenes Release-Repo) |

**Rechtlicher Hinweis:** Die Wortmarke *owntv* und das Wortmarken-Asset `owntv_wordmark.png`
gehören Upstream und dürfen in Sportio-Logo **nicht** verwendet werden. Siehe §2.7.

---

## 2. Logo & Wortmarke

### 2.1 Wortmarke

| Eigenschaft | Wert |
|---|---|
| **Schreibweise** | `Sportio` — großes S, der Rest klein. Punkt immer. |
| **Falsch** | `SPORTIO`, `Sport IO`, `sportio`, `SportioTV`, `SportTV` |
| **Anrede (Anfang)** | **S**portio, **d**ie Sportio-App |
| **Anrede (Possessiv)** | Sportios |
| **Schrift** | **Plus Jakarta Sans ExtraBold (wght 800)** — dieselbe Variable-Font-Datei, die die App rendert (`res/font/plus_jakarta_sans_variable.ttf`) |
| **Laufweite** | **−18 Einheiten je 1000/em = −1,8 %** |
| **Optisches Kerning** | `S│p` +10 · `t│i` +12 Einheiten |
| **Versalung im UI** | Nie. Immer `Sportio`. |

**Gemessene Metrik** (Font-Einheiten, 1 em = 1000):

| | Wert |
|---|---|
| Versalhöhe (Cap) | 788,3 |
| x-Höhe | 573 |
| Abstand Unterlänge (`p`) | 200 |
| Tintenbox Breite × Höhe | **3604,4 × 988,3** |
| Seitenverhältnis | 3,65 : 1 |

**Farbteilung — das ist die Entscheidung, die die Marke trägt:**

```
Sport   →  Signatur-Grün          io  →  Neutral
```

`Sport` trägt die Bedeutung und bekommt die Markenfarbe, `io` schließt das Wort neutral ab.
Ein einfarbiges Logo wäre korrekt, ein halbgrünes ist **wiedererkennbar**.

### 2.2 Wortmarken-Varianten

| Variante | Verwendung | `Sport` | `io` |
|---|---|---|---|
| **Dark (Standard)** | dunkler Hintergrund, App-UI, Splash, Marketing | `#4FEE94` | `#EAF7EF` |
| **Light** | Light-Theme, Print, Doku | `#0E6B41` | `#081C11` |
| **Mono weiß** | über Video, auf Grün, Stempel | `#FFFFFF` | `#FFFFFF` |
| **Mono schwarz** | Print einfarbig | `#000000` | `#000000` |
| **Mono grün** | einfarbig auf Weiß | `#4FEE94` | `#4FEE94` |

**Konstruktionsregeln:** Die Buchstaben sind unveraenderte Glyphen der Markenschrift —
**keine** modifizierten, ausgeschnittenen oder neu gezeichneten Formen. Nur Tracking und optisches
Kerning weichen ab. Jede Abweichung macht das Logo zu einem anderen Logo.

### 2.3 Die Marke

Die Marke ist das Quadrat mit dem Play-Dreieck. Sie trägt die App allein, wenn kein Platz für
die Wortmarke ist (Launcher, Favicon, Rail bei 36 dp).

**Aufbau (256-Einheiten-Raster):**

| Element | Wert |
|---|---|
| Kachel | Abgerundetes Quadrat 256 × 256, Eckenradius **57,6** (22,5 % der Kante) |
| Verlauf | `#4FEE94` → `#134D2C`, diagonal 160° |
| Glanz | Weiß 18 % bei y = 0, linear auf 0 bei **y = 158,7** (62 % der Höhe) — kein sichtbarer Schnitt |
| Play-Dreieck | 108 breit × 116 hoch, Eckenradius 14, Start `x = 76`, `y = 70` |
| Optische Korrektur | Dreieck **2 Einheiten nach rechts** — die Spitze wiegt schwer, geometrisch zentriert wirkt es links |

> **Warum ein Glanz und keine harte Bande:** Die erste Fassung clippt den Glanz auf die obere
> Hälfte. Das erzeugt eine waagerechte Naht genau in der Mitte des Icons — auf dem Launcher
> sichtbar, im Store-Listing peinlich. Der Alpha-Verlauf auf 62 % löst das ohne Kante.

### 2.4 Lockup-Zusammenbau

| Verhältnis | Wert | Grund |
|---|---|---|
| Versalhöhe Wortmarke / Kachel | **0,60** | Bei 0,66 wirkt die Wortmarke zu klein neben der schweren Kachel |
| Abstand Marke → Wortmarke | **0,24 × Kachel** | Upstream-Konvention, trägt gut |
| Schutzraum außen | **x-Höhe** (110,5 Einheiten) | siehe §2.5 |
| Vertikale Ausrichtung | Cap-Box auf Kachelmitte, **6 Einheiten angehoben** | Die Unterlänge der `p` würde sonst optisch absacken |

### 2.5 Schutzraum & Mindestgröße

```
        ┌────────────────────────────────────┐
        │          ⟵ x-Höhe = 573 ⟶          │   Schutzraum oben
        │   ┌────────────────────────────┐   │
        │   │        ▛▀▀▀▀▀▀▀▜   Sportio │   │
   ⟵ x  │   │        ▌      ▜           │   │ ⟶ x
   höhe │   │        ▙      ▟           │   │      = Höhe des Kleinbuchstaben `o`
        │   │        ▜      ▛           │   │
        │   │        ▝▄▄▄▄▄▄▄▘           │   │
        │   └────────────────────────────┘   │
        │          ⟵ x-Höhe ⟶                │   Schutzraum unten
        └────────────────────────────────────┘
```

- **Schutzraum:** die x-Höhe auf allen vier Seiten. Nichts anderes in diesem Bereich.
- **Mindestgröße:** Wortmarke **32 px** Cap-Höhe auf TV · Print **12 mm** · Marke **16 px**
  (dann ohne Glanz).

### 2.6 Icon-Varianten

| Variante | Kachel | Dreieck | Einsatz |
|---|---|---|---|
| **Pitch** (Standard) | `#4FEE94` → `#134D2C` | `#F3FCF7` | Store, Launcher, Splash |
| **Light** | `#0E6B41` → `#C8EFD8` | `#081C11` | Print, Doku |
| **Mono** | `#FFFFFF` flach | `#060B07` | Stempel, Wasserzeichen |
| **Pokal** (Marketing) | `#19CC67` → `#0B3D22` | `#F3FCF7` | Social, Store-Feature-Graphic |

**Regeln:**
- **Ab 72 px** wird der Glanz gezeichnet. Darunter fällt er weg — bei 48 px ist er ein Grauschleier.
- Der Icon variiert **nie** zwischen Light und Dark je nach Umgebung — der Launcher-Hintergrund
  ist unvorhemsagbar.
- **Adaptive Icon (Android TV):** Vordergrund = Marke in der zentralen 66 %, Hintergrund `#134D2C`.
- **TV-Banner 320 × 180:** Pitch-Verlauf, Wortmarke weiß (cap 40), Marke 64, Rand 24.

### 2.7 Verboten

- ❌ Logo färben, verzerren, rotieren, schattieren, umrahmen
- ❌ Wortmarke in einer anderen Schrift oder mit veränderten Glyphen
- ❌ Tracking enger als −1,8 % (der `r│t`-Übergang kollabiert sonst)
- ❌ Upstream-Wortmarke `owntv` oder die `owntv_*`-Drawables in Sportio-Kontexten
- ❌ Logo auf einer Farbe mit Kontrast < 4,5:1 zum Hintergrund
- ❌ Logo über einem Foto ohne Scrim (≥ 62 % Schwarz, siehe Board-Zelle „Over a still")
- ❌ „Powered by Sportio", „by Sportio" — die App steht für sich

### 2.8 Dateien

Alle Assets liegen unter [`assets/`](./assets/), erzeugt von
[`tools/brand/SportioLogo.java`](../../tools/brand/SportioLogo.java):

```
assets/svg/     sportio-logo{,-light,-mono-white,-mono-black,-stacked}.svg
                sportio-wordmark{,-light,-mono-white,-mono-black,-mono-green}.svg
                sportio-mark{,-light,-mono}.svg
                sportio-banner-320x180.svg
assets/png/     sportio-logo{,-light}.png   sportio-wordmark{,-light}.png
                sportio-mark-{48,72,96,192,512}.png
                sportio-banner-320x180@4x.png   logo-board.png (Übersicht)
assets/android/ sportio_mark.xml, sportio_wordmark.xml  (VectorDrawable)
```

**Neu bauen:**

```powershell
$env:JAVA_HOME="C:\Users\<user>\OwnTV\tools-setup\jdk17-portable\jdk-17.0.11+9"
& "$env:JAVA_HOME\bin\javac.exe" -d out tools\brand\SportioLogo.java
& "$env:JAVA_HOME\bin\java.exe" "-Djava.awt.headless=true" -cp out SportioLogo
```

Der Generator liest die Variable-Font-Datei der App direkt und fährt die `wght`-Achse über
`Font.deriveFont(Font.BOLD)` — Kontrollstamm 75 → 106 Einheiten, also wirklich 800, nicht 700.
Es ist **kein** Outlining-Tool nötig und keine Fremdbibliothek.

---

## 3. Ton & Stimme

### 3.1 Drei Stimmen

| Stimme | Wann | Beispiel |
|---|---|---|
| **Direkt** | Alltag: Labels, Buttons, Listen | `Play`, `Standings`, `Retry` |
| **Erklärend** | Einstellungen, Hilfe, leere Zustände | „Kein passender Sender in deinen Wiedergabelisten." |
| **Sportlich** | Live-Indikatoren, Counts, Dramaturgie | `LIVE`, `2:1`, `68′`, `Full time` |

Die Standardstimme ist **direkt**. Erklärend nur, wo der Nutzer sonst raten muss.

### 3.2 Tonregeln

1. **Deutsch ist die Arbeitssprache der Marke.** Die App ist zweisprachig (EN/DE, plus 25 weitere),
   aber Markentexte, Store-Listing und Doku werden zuerst auf Deutsch verfasst, dann auf Englisch
   übersetzt — nicht umgekehrt. (Grund: sportliche Terminologie ist im Deutschen präziser;
   „Anpfiff" ≠ „kick-off" in der Tonlage.)
2. **Konstant auf Du.** Nie „Sie", nie „der Nutzer". Beispiele: „Wähle deine Lieblingsclubs",
   nicht „Wählen Sie Ihre Lieblingsclubs".
3. **Kein Jargon ohne Auflösung.** „Catch-up" wird beim ersten Mal erklärt, danach darf es stehen.
4. **Kein Fachjargon-Filter für Serien/Episoden**, wenn der Nutzer sie nicht sucht: „Folge 3",
   nicht „S01E03".
5. **Kein Schelten bei Fehlern.** Ein Fehler ist ein Zustand, kein Vorwurf.
6. **Zahlen sprechen lassen.** „3 Spiele heute" statt „Es liegen mehrere Spiele vor".
7. **Ein Wort, wenn ein Wort reicht.** „Retry", nicht „Erneut versuchen" (Ausnahme: leere Zustände).

### 3.3 Tonalitäts-Quick-Reference

| ✅ So | ❌ Nicht so |
|---|---|
| `Kein Spiel heute` | `Leider keine Spiele vorhanden` |
| `Sender wird gesucht…` | `Fehler beim Laden der Sender` |
| `Full time` | `Spiel beendet! Endstand 2:1` |
| `Nur Audio verfügbar` | `Dieser Stream enthält kein Videobild` |
| `Spielstand 1:0, 34. Minute` | `TOR! 🎉` |
| `Keine Verbindung zum Sport-Dienst` | `ESPN API Error 403` |
| `Tippe OK, um den Sender zu starten` | `Kanal starten?` |
| `Heute · 20:30` | `Spiel 1 heute um 20:30 Uhr` |

---

## 4. Text & Redaktion

### 4.1 Sprache & Interpunktion

| Regel | Richtwert |
|---|---|
| Anrede | **du** (DE), **you** (EN) |
| Auslassungspunkte | `…` (U+2026), **ein** Zeichen, nie drei Punkte |
| Bindestrich | Halbgeviertstrich `-`, nicht `–` in UI-Labels |
| Anführungszeichen | `"…"` in DE, `"…"` in EN — keine Anführungszeichen in Button-Labels |
| Geschützte Leerzeichen | zwischen Zahl und Einheit: `20:30 Uhr`, `1,2 MB/s` |
| Dezimaltrennzeichen | Lokalisiert (`1,2` DE / `1.2` EN) |
| Ausrufezeichen | **nie** in UI-Text |
| Versal | Buttons und Labels in **Sentence case**, nie GROSSBUCHSTABEN (Ausnahme: `LIVE`, `REC`) |

### 4.2 Zahlen, Zeit und Einheiten

| Kontext | Format | Beispiel |
|---|---|---|
| Uhrzeit live | 24 h, lokale Zone | `20:30` |
| Uhrzeit mit Zone | 24 h + Zone | `20:30 CET` |
| Datum | Locale | `Sun, Oct 12` (EN) · `So., 12. Okt.` (DE) |
| Wochentag relativ | `Today` / `Morgen` / `Gestern` | `Tomorrow · 19:30` |
| Spielminute | `'` als Apostroph | `68′`, Halbzeit `HT` |
| Endstatus | `FT`, Verlängerung `AET`, Elfmeterschießen `PEN` | `FT 2:1` |
| Laufzeit | `h` + `min` | `1 h 25 min` |
| Dateigröße | 1 Dezimalstelle, Locale | `12.4 MB` (EN) · `12,4 MB` (DE) |
| Rate | 1 Dezimalstelle + Einheit | `12.4 MB/s` |
| Zähler | Locale-tausender | `12 480` (DE) · `12,480` (EN) |
| Spielstand | `Heim:Gast` mit Doppelpunkt | `2:1` |

**Regel:** Zahlen werden **lokalisiert formatiert** (`NumberFormat`/`DateTimeFormatter`),
niemals per String-Konkatenation. Bei 0 und 1 wird die Pluralform der Ziel-Sprache benutzt
(„1 Spiel", „0 Spiele"; „1 minute left", „0 minutes left").

### 4.3 Buttons

| Eigenschaft | Regel |
|---|---|
| Länge | 1–3 Wörter, max. 18 Zeichen (DE) / 16 (EN) |
| Verb | Lead-Verb, kleingeschrieben: `Play`, `Watch`, `Starten`, `Weiter` |
| Verboten | `OK`, `Yes`, `Submit`, `Click here` |
| Bei Aktion mit Objekt | Objekt anhängen: `Sportio einrichten`, `Kanal starten` |
| Rückgängig | Immer `Back`, nie `Cancel` allein (es wird nichts abgebrochen, nur verlassen) |

**Vorlagen:**

```
Primär        Play            Starten           Sportio einrichten
Sekundär      Trailer         Vorschau          Weiter
Tertiär       Details         Details           Zurücksetzen
Text/Link     Remind me       Erinnern          Hilfe öffnen
Destruktiv    Clear history   Verlauf löschen   Konto löschen
Status        Retry           Erneut versuchen  Erneut laden
```

### 4.4 Beschriftungen (Sentence case, keine Sentenz)

```
Live TV            Movies & Series       My teams
Standings          Broadcasts            Upcoming
Follow team        Remove               Channels
Today              Yesterday            Venue
```

### 4.5 Leerzustände

Leer ist ein **gültiger, gestalteter Zustand** — nie eine weiße Fläche, nie „0".

**Formel:** *(Was ist leer)* + *(warum das normal ist)* + *(was der Nutzer tun kann)*

```
┌─────────────────────────────────────────────────┐
│                                                 │
│              ⚽  No games today                  │
│                                                 │
│    The Bundesliga has no fixtures on             │
│    Tuesday. Check back tomorrow.                │
│                                                 │
│              [ Show other leagues ]             │
│                                                 │
└─────────────────────────────────────────────────┘
```

Weitere Beispiele:

| Situation | Text (EN) | Text (DE) |
|---|---|---|
| Keine Favoriten | **No favourites yet** · Long-press any channel to add it here. | **Noch keine Favoriten** · Halte eine Kanaltaste gedrückt, um sie hinzuzufügen. |
| Kein Kanal-Match | **No matching channel in your playlists** · Sportio searches your channel names and guide. | **Kein passender Sender in deinen Wiedergabelisten** · Sportio durchsucht Sendernamen und EPG. |
| Keine EPG-Daten | **No guide data yet** · Add a TV guide source in Settings › Sources & guide. | **Noch keine EPG-Daten** · Füge in Einstellungen › Quellen & TV-Guide eine Quelle hinzu. |
| Kein Download | **Nothing downloaded** · Downloads appear here while they run. | **Nichts heruntergeladen** · Downloads erscheinen hier, während sie laufen. |
| Suchergebnis leer | **No results for “{query}”** · Try a shorter term or another section. | **Keine Ergebnisse für „{query}“** · Versuche einen kürzeren Begriff oder einen anderen Bereich. |

### 4.6 Fehlermeldungen

**Formel:** *Was ist passiert* + *(technischer Grund in Klammern, wenn nötig)* + *was der Nutzer tun kann*

```
❌  Sports data could not be loaded
    The service did not answer.                  [ Retry ]

❌  This stream failed
    All four playback methods were refused.      [ Try another channel ]

⚠   Playlists are out of date
    Last successful sync was 3 days ago.         [ Sync now ]

⚠   Catch-up ended 2 hours ago
    This channel keeps no archive beyond 24 h.   [ Watch live ]
```

**Regeln:**

| ✅ | ❌ |
|---|---|
| `No connection to the sports service` | `Network error 0x7F03` |
| `The password was wrong` | `HTTP 401 Unauthorized` |
| `This channel plays audio only` | `No video track found` |
| `Sportio needs 2 GB free to record` | `Insufficient disk space (ENOSPC)` |
| `Guide data from this source is out of date` | `XMLTV parse failed at line 412` |

**Keine Error-Codes, Stacktraces, Exception-Klassen oder URLs im sichtbaren Text.**
Sie gehören in den Error log (Mehr › Error log) — dort sind sie *sogar* willkommen.

### 4.7 Status- und Leertext-Dictionary (verbindlich)

| Konzept | EN | DE |
|---|---|---|
| live | `LIVE` | `LIVE` |
| live mit Zeit | `LIVE · 68′` | `LIVE · 68′` |
| heute | `Today` | `Heute` |
| morgen | `Tomorrow` | `Morgen` |
| gestern | `Yesterday` | `Gestern` |
| demnächst | `Upcoming` | `Demnächst` |
| ergebnis | `Results` | `Ergebnisse` |
| endstand | `Full time` | `Abpfiff` |
| anpfiff | `Kick-off` | `Anpfiff` |
| tabelle | `Standings` | `Tabelle` |
| meine teams | `My teams` | `Meine Teams` |
| folgen | `Follow` | `Folgen` |
| nicht mehr folgen | `Unfollow` | `Nicht mehr folgen` |
| sender | `Channels` | `Sender` |
| spielort | `Venue` | `Spielort` |
| tv-sender | `TV` | `TV-Sender` |
| lade | `Loading…` | `Wird geladen…` |
| leer (allgemein) | `No games` | `Keine Spiele` |
| | `Sports data could not be loaded` | `Sportdaten konnten nicht geladen werden` |
| | `Retry` | `Erneut versuchen` |
| | `Play` | `Abspielen` |
| | `Details` | `Details` |
| | `No matching channel in your playlists` | `Kein passender Sender in deinen Wiedergabelisten` |

> **Übersetzungsregel:** Proper Nouns (Vereine, Ligen, Spielorte, Sponsoren) werden **nie**
> übersetzt — sie kommen unverändert aus der API. Satzaround um sie herum schon.
> Dieser Satz ist in allen 27 Sprachfassungen identisch.

---

## 5. Farbe

> Alle Werte WCAG 2.1 geprüft. Prüfprotokoll: [`sportio.tokens.json`](./sportio.tokens.json).
> Jede Textpaarung ≥ 4,5:1, jede Nicht-Text-Paarung ≥ 3:1 — **in beiden Themes**.

### 5.1 Der Grüntum

**Sportio Grün ist der Rasen unter Flutlicht.** Kein Neon, kein Gift — gesättigt genug, um
einen 2-px-Fokusring auf fast Schwarz zu tragen, ruhig genug, um dahinter Fließtext zu setzen.

```
Signatur-Farbe (dark):   #4FEE94      Hue 146 · S 82 % · L 62 %
Signatur-Farbe (light):  #0E6B41      Hue 146 · S 76 % · L 24 %
```

**Hue-Wahl gegen Upstream:** OwnTV-Petrol liegt bei Hue 172, Sportio bei 146 — ein
Viertelumdrehung Abstand. Kein Clash, wenn beide Apps auf einem Gerät nebeneinander stehen,
aber sofort unterscheidbar. Ein Prüflauf über die Hue-Achse bestätigte: bei Hue 150 fällt der
Kontrast zwischen beiden auf 1,01 (visuell identisch), bei 146 bleiben beide klar trennbar
und das Grün wirkt noch „Rasen" statt „Petersilie".

### 5.2 Grün-Rampe

| Token | Hex | Lightness | Einsatz |
|---|---|---|---|
| `P0` | `#F3FCF7` | 97 % | hellster Wert, Hintergründe auf Light |
| `P10` | `#DEF8E9` | 92 % | Text auf hellen Containern |
| `P20` | `#B5F2D0` | 83 % | Light-Accent-Container |
| `P30` | `#81EEB0` | 72 % | **Fokusring (dark)** |
| `P40` | `#4FEE94` | 62 % | **Signatur (dark)**, Icon, Hero |
| `P50` | `#30E880` | 55 % | Verlauf-Mitte |
| `P60` | `#19CC67` | 45 % | Verlauf-Start, „gefolgt"-Zustand |
| `P70` | `#16A252` | 36 % | Light-Töne der Rampe |
| `P80` | `#147B41` | 28 % | Rampe Light-Ende |
| `P90` | `#10502C` | 19 % | **Accent-Container (dark)** |
| `P95` | `#0C311C` | 12 % | Text auf Accent (dark) |
| `P100` | `#081C11` | 7 % | **Text auf Accent (dark)**, Light-Textfarbe |

**Regeln zur Rampe:**
- Nur die 12 dokumentierten Töne sind gültig. Keine Zwischenwerte, keine erzeugten Farben.
- Töne werden **nie** untereinander gemischt, um „etwas dazwischen" zu bekommen.
- Töne P40 / P80 sind die einzigen, die als große Fläche (Button, Hero) dienen.

### 5.3 Dark Theme (Standard)

Die App startet und bleibt in Dark. Grund: der Fernseher ist dunkel, und die Panels müssen
sich vom Bild abheben, nicht mit ihm verschmelzen.

| Rolle | Hex | Kontrast auf `page` | Verwendung |
|---|---|---|---|
| `page` | `#060B07` | — | Seitenhintergrund |
| `surface` | `#0B120D` | 1,06 | Flächenbasis |
| `surface1` | `#101A13` | 1,15 | Zeilen, Listen |
| `surface2` | `#16241B` | 1,26 | Karten |
| `surface3` | `#1E3025` | 1,48 | gehobene Karten |
| `surface4` | `#28402F` | 1,88 | Menü, Sheet |
| `outline` | `#3C5A48` | 2,16 | Rahmen |
| `outlineVar` | `#1E3126` | 1,33 | Trennlinien |
| `text` | `#EAF7EF` | **17,99 ✓AAA** | Überschrift, Haupttext |
| `muted` | `#A6C3AF` | **10,43 ✓AAA** | Metazeile, Sekundärtext |
| `primary` | `#4FEE94` | **13,21 ✓AAA** | Akzent, Fokus, Buttons |
| `onPrimary` | `#081C11` | **11,81 ✓AAA** (auf `primary`) | Text auf Akzent |
| `primaryCont` | `#134D2C` | — | Akzent-Container, Badge-Grund |
| `onPrimaryCont` | `#9BF5BE` | **7,62 ✓AAA** | Text auf Container |
| `focus` | `#81EEB0` | **13,97 ✓AAA** | Fokusring, 2–3 px |
| `glassTint` | `#0E1A12` | — | Glas-Füllung (Standard-Alpha 0,56) |
| `glassOff` | `#101614` | — | Glas aus (Fallback-Füllung) |
| `scrim` | `rgba(2,6,4,0.62)` | — | Blende hinter Sheet/Dialog |

> `dim` (`#7E9C88`) steht hier **nicht**, weil M3 diese Rolle nicht kennt. Siehe §5.5.

> `surface4` ist die höchste reguläre Stufe. Für Fokusflächen und Menüs darüber hinaus
> gilt: nur Text/Icon in Akzentfarbe, nie eine neue Flächenstufe erfinden.

### 5.4 Light Theme

| Rolle | Hex | Kontrast auf `page` | Verwendung |
|---|---|---|---|
| `page` | `#F4F8F4` | — | Seitenhintergrund |
| `surface` … `surface4` | `#FFFFFF` → `#DCE6DF` | 1,04 → 1,21 | Flächen |
| `outline` | `#A8BBAE` | 1,93 | Rahmen |
| `text` | `#081C11` | **16,53 ✓AAA** | Haupttext |
| `muted` | `#3C5546` | **7,59 ✓AAA** | Sekundärtext |
| `primary` | `#0E6B41` | **6,12 ✓AA** | Akzent |
| `onPrimary` | `#FFFFFF` | **6,57 ✓AA** (auf `primary`) | Text auf Akzent |
| `primaryCont` | `#C8EFD8` | — | Akzent-Container |
| `onPrimaryCont` | `#08301C` | **11,58 ✓AAA** | Text auf Container |
| `focus` | `#0E6B41` | **6,12 ✓AA** | Fokusring |

> `dim` (`#5E7566`) steht hier **nicht**, weil M3 diese Rolle nicht kennt. Siehe §5.5.

> **Warum Light nicht `#4FEE94`:** Ein Prüflauf über die Lightness-Achse (L 46 → 26) zeigte:
> Weiß auf dem hellen Grün fällt erst bei **L 30** (4,53:1) über die AA-Linie. `#4FEE94` auf
> `#F4F8F4` erreicht nur 1,89:1 — als Buttonfläche unbrauchbar. Light-Theme nutzt deshalb
> `P80`-Tiefe (`#0E6B41`), Dark-Theme `P40`. **Gleicher Hue, andere Tiefe.**

### 5.5 Die Stage-Schicht

Material 3 kennt **keine** Rolle `dim`, `body` oder `navText`. Die Stage-Oberfläche (Rail, Sheets,
Menüs, Tastenhinweise) hat sie trotzdem — sie liegen in `StagePalette` in `StageTokens.kt`, nicht
in der M3-Palette. Deshalb sind sie hier getrennt ausgewiesen; die `dim`-Werte oben in §5.3/§5.4
verweisen auf diese Tabelle.

| Rolle | Dark | auf Seite | auf Fläche | Light | auf Seite | auf Fläche |
|---|---|---|---|---|---|---|
| `text` | `#F1F6F3` | 18,15 ✓AAA | 10,31 ✓AAA | `#0F1A16` | 16,61 ✓AAA | 13,93 ✓AAA |
| `muted` | `#A9B8AF` | 9,60 ✓AAA | 5,45 ✓AA | `#4C5A54` | 6,76 ✓AA | 5,67 ✓AA |
| **`dim`** | `#7E9C88` | 6,61 ✓AA | 3,75 ✓ | `#5E7566` | 4,65 ✓AA | 3,90 ✓ |
| `itemText` | `#D8E2DC` | 14,95 ✓AAA | 8,49 ✓AAA | `#1C2824` | 14,21 ✓AAA | 11,92 ✓AAA |
| `menuItemText` | `#DEE7E1` | 15,70 ✓AAA | 8,92 ✓AAA | `#1C2824` | 14,21 ✓AAA | 11,92 ✓AAA |
| `tagText` | `#C6D3CC` | 12,84 ✓AAA | 7,29 ✓AAA | `#3D4B45` | 8,55 ✓AAA | 7,17 ✓AAA |
| `body` | `#CBD6D0` | 13,29 ✓AAA | 7,55 ✓AAA | `#2D3A35` | 11,08 ✓AAA | 9,29 ✓AAA |
| `navText` | `#BCCBC2` | 11,77 ✓AAA | 6,68 ✓AA | `#55635D` | 5,89 ✓AA | 4,93 ✓AA |
| `fxLine` | `#DCE9E2` | 15,87 ✓AAA | 9,01 ✓AAA | `#2D3A35` | 11,08 ✓AAA | 9,29 ✓AAA |
| `ok` | `#7BE3A4` | 12,63 ✓AAA | 7,17 ✓AAA | **`#1B743C`** | 5,39 ✓AA | 4,55 ✓AA |
| `warn` | `#FFB86B` | 11,64 ✓AAA | 6,61 ✓AA | **`#8B5904`** | 5,52 ✓AA | 4,66 ✓AA |
| `danger` | `#FF8A7A` | 8,66 ✓AAA | 4,92 ✓AA | **`#BD2C1F`** | 5,49 ✓AA | 4,64 ✓AA |
| `ratingStar` | `#FFCC4D` | 13,22 ✓AAA | 10,75 ✓AAA | **`#906A09`** | 4,58 ✓AA | 4,94 ✓AA |

**Vier Light-Werte weichen vom Upstream ab — mit Grund:**

| Rolle | Upstream | Problem | Sportio |
|---|---|---|---|
| `ok` | `#2B8A3E` | 4,08:1 auf der Seite | `#1B743C` |
| `warn` | `#9A5A08` | 4,28:1 auf der Fläche | `#8B5904` |
| `danger` | `#D23B2E` | 4,45:1 auf der Seite | `#BD2C1F` |
| `ratingStar` | `#FFCC4D` | **1,40:1** auf der hellen Seite | `#906A09` |

> Der Upstream-Kommentar sagt, der Rating-Chip bleibe „in light unverändert". Er ist es nicht:
> `#FFCC4D` auf `#F4F7F5` ergibt 1,40:1 und ist damit **kein gültiges Text-Gold**. Das fällt nur
> nicht auf, weil auf hellen Karten ohnehin niemand hinschaut. Die vier Werte wurden per
> HSL-Suche neu gelöst (Hue bleibt, Lightness sinkt, bis jedes Paar AA erreicht).

Weitere Stage-Werte: `ink` (die Basis für alle flächigen Alphawerte) `#FFFFFF` / `#0C1814` ·
Scrim `rgba(2,6,4,0.55)` dark / `rgba(20,32,28,0.30)` light ·
Wash = Seite · `knobOff` `#CFD8D4` / `#FFFFFF` · `segmentOn` Weiß 13 % / `#FFFFFF` ·
`keycap` Weiß 8 % / `#FFFFFF` · `chipWarnText` `#FFD27A` / `#8B5904` ·
`chipWarnFill` `rgba(255,200,80,0.14)` / `rgba(139,89,4,0.12)`.

### 5.6 Semantische Farben

| Bedeutung | Dark | Light | Auf | Respektiert |
|---|---|---|---|---|
| **LIVE** | `#FF5A5F` | `#C42127` | `#FFFFFF` | pulsierender Punkt + Wort |
| **Aufnahme (REC)** | `#FF3B5C` | `#C81038` | `#FFFFFF` | REC-Glyphe |
| **Warnung** | `#FFB020` | `#7A4D00` | `#221600` / `#FFFFFF` | ⚠-Glyphe |
| **Info** | `#3FD0E8` | `#0E6B7D` | `#001F24` / `#FFFFFF` | i-Glyphe |
| **OK** | `#3FD07A` | `#1B7A45` | `#081C11` / `#FFFFFF` | ✓-Glyphe |
| **Fehler** | `#FF8A86` | `#C42127` | `#FFFFFF` | ⚠-Glyphe + Text |

**Hierarchie der Warnfarben — nicht verhandelbar:**

```
LIVE-Rot      ⟶  das einzige Rot, das „jetzt" bedeutet
REC-Magenta   ⟶  das zweite Rot, immer mit REC-Glyphe
Fehler-Rot    ⟶  nur in Fehlertexten, nie als Fläche
Amber         ⟶  Warnung, nie Zerstörung
```

**Regeln:**
- **Rot ist reserviert.** Kein Rot für Dekoration, Kategorien, Tags oder „Abo".
- **Live und Recording dürfen nie gleichzeitig dieselbe Fläche tragen** — sie sind 2 Hue-Punkte
  entfernt und bei Deuteranopie praktisch gleich. Immer Glyphe mitliefern.
- Keine Statusfarbe jenseits 3:1 gegen ihre Trägerfläche.
- Semantik wird **nie** nur über Farbe transportiert (§12.1).

### 5.7 Team- und Ligafarben

Vereinsfarben kommen aus der ESPN-API und sind nicht kontrollierbar. Deshalb: **niemals die
Rohfarbe anzeigen**, sondern immer den passenden Ton aus dem sicheren Satz.

| Farbfamilie | Dark | Light | Klartext |
|---|---|---|---|
| Orange | `#ED926E` | `#AC4015` | 8,44 / 5,60 |
| Amber | `#EAB771` | `#A96D19` | 10,87 / 4,01 |
| Hellblau | `#74C2E7` | `#1D7AA5` | 10,02 / 4,47 |
| Blau | `#7697E5` | `#1F47A3` | 6,92 / 7,87 |
| Indigo | `#8F7BE0` | `#3D259D` | 5,74 / 10,04 |
| Violett | `#BA7CDE` | `#70279B` | 6,64 / 7,98 |
| Karmin | `#E57692` | `#A31F40` | 6,93 / 6,93 |
| Schiefer | `#A2ABB9` | `#535F6E` | 8,56 / 6,06 |
| Grün | `#7BE0A0` | `#259D51` | 12,29 / 3,25 |

**Anwendung:** Nimm den Hue der Vereinsfarbe, bilde daraus das Paar (Light-Ton für die
dunkle Seite, Dark-Ton für die helle). Ein schwarzes Vereinswappen wird auf Dark nie als
Text verwendet, sondern auf einer `surface3`-Platte als Fläche mit weißem Rand.

Teamfarben erscheinen **nur** als: Wappen-Fläche, 6-px-Balken an einem Fixture-Row,
Punkt neben dem Namen. Nie als Button, nie als Fokusfarbe, nie als Textfarbe.

### 5.8 Verläufe

| Verlauf | Werte | Einsatz |
|---|---|---|
| **Pitch Hero** | `#4FEE94` → `#10492B`, 160° | Hero-Bereich, Splash |
| **Pitch Icon** | `#4FEE94` → `#134D2C`, 160° | App-Icon |
| **Pokal** | `#19CC67` → `#0B3D22`, 160° | Marketing |
| **Live-Sweep** | `#4FEE94` → transparent, 0 → 62 % | Fokus-Hover auf Live-Zeile |

Verläufe **nur** in der Diagonale 160°. Kein horizontaler, kein radialer Verlauf.
Kein Verlauf hinter Text ohne Scrim (≥ 62 % Schwarz bzw. Weiß).

### 5.9 Glas & Material

| Preset | Alpha | Blur | Einsatz |
|---|---|---|---|
| Ultra Clear | 0,24 | 0,35 | maximales Bild-Durchscheinen |
| Clear | 0,38 | 0,62 | Filmplakat-Backdrops |
| **Balanced** (Default) | 0,56 | 0,78 | Rail, Sheets, Popups |
| Tinted | 0,74 | 0,88 | dichte Datenflächen |
| Opaque | 0,92 | 1,00 | Glass aus (Fallback) |

- Glas-Füllung: `glassTint` bei gewählter Alpha · 1 px Highlight oben (`white 10 %`)
  · 1 px Rim (`ink 7 %`) · Schatten `0 24 60` bei 45 % Schwarz (Dark) bzw.
  `rgb(20,40,32) 16 %` (Light).
- **Über Inhalt** (Rail, Menüs, Popups) nie unter 55 % Deckkraft, damit Wortreste dahinter
  nie lesbar sind. Skalierung: `0.55 + 0.45 × alpha`.
- Realer Backdrop-Blur nur ab **API 31**. Darunter: die Alpha-Stufe allein.

### 5.10 Farb-Konflikte (Sportio)

| Konflikt | Regel |
|---|---|
| Sportio-Grün `#4FEE94` vs. Team-Grün | Team-Grün ist nie der Akzent. In Sport-Elementen gewinnt der Akzent; Teamfarbe tritt nur als 6-px-Balken auf. |
| Sportio-Grün vs. „gefolgt"-Zustand | Folge-State nutzt **P60** `#19CC67` plus gefüllten Stern, nie den Fokus-Akzent. |
| Live-Rot vs. Fehler-Rot | Fehler erscheinen als Text+Icon, nie als pulsierende Fläche. |
| Logo-Grün auf Video | Nutze `mono weiß` oder eine dunkle Scrim-Fläche (§2.7). |

---

## 6. Typografie

### 6.1 Schriftfamilien

| Rolle | Familie | Einsatz | Lizenz |
|---|---|---|---|
| **Display / UI** | **Plus Jakarta Sans** | gesamte App, Popups, Zahlen | OFL 1.1 |
| **Numerisch** | Plus Jakarta Sans Tabular | Spielstände, Uhr, Timer | OFL 1.1 |
| **Monospace** | JetBrains Mono | Log-Ausgaben, EPG-Debug | OFL 1.1 |
| **Fallback** | Roboto → Noto Sans → `sans-serif` | fehlende Glyphen, RTL | — |

**Regeln:**
- Plus Jakarta Sans ist in **allen** Gewichten 400–800 als **Variable Font** eingebettet —
  kein synthetisches Fett. Das Logo nutzt 800, Fließtext 400.
- **Tabular Figures** für alle Zahlen, die sich nicht verschieben dürfen:
  Spielstand, Uhr, Countdown, Timer, Tabellenwerte, Kanalnummer. Proportionale Ziffern
  springen sonst bei jedem Update.
- Zahlen in Serifenlose-Schrift niemals mit `letterSpacing` versehen.
- Keine zwei Familien im selben Screen-Abschnitt. Ausnahme: Monospace im Diagnose-Log.

### 6.2 Skala

Die App wird bei 1920 × 1080 / 320 dpi / 90 % UI-Zoom entwickelt (Default). Alle Werte sind
in **sp** bei diesem Setup angegeben; der Code nutzt zusätzlich Mockup-Pixel (1 mpx = 0,556 dp).

| Token | Größe | Zeilenhöhe | Gewicht | Stage-mpx | Einsatz |
|---|---|---|---|---|---|
| `display` | 25,6 | 30,7 | 800 | 46 | Hero-Spielstand, Matchday-Headline |
| `h1` | 16,7 | 21,1 | 800 | 30 | Screen-Titel |
| `h2` | 13,9 | 17,8 | 700 | 25 | Sektionstitel, Sheet-Titel |
| `h3` | 12,2 | 16,1 | 700 | 22 | Gruppen-Header, Panel-Titel |
| `title` | 11,1 | 15,0 | 600 | 20 | Karten-/Zeilentitel |
| `body` | 10,0 | 13,9 | 500 | 18 | Fließtext, Spielstandzeile |
| `bodySm` | 9,4 | 13,3 | 600 | 17 | Button-Label |
| `label` | 8,3 | 11,7 | 600 | 15 | Chip, Tag, Metazeile |
| `micro` | 7,8 | 11,1 | 500 | 14 | Zeitstempel, Hilfetext |

**Absolute Untergrenze: `micro` = 7,8 sp.** Alles darunter ist auf einem Fernseher nicht lesbar.
Für 27 Sprachen gilt: `micro` ist der Wert, an dem **Arabisch und Thai** zuerst brechen —
prüfe diese beiden Locale bei jeder Typo-Änderung (siehe `tools/i18n/check_text_overflow.py`).

### 6.3 Zeilenlänge & Trunkierung

| Kontext | Max. Zeilen | Verhalten |
|---|---|---|
| Screen-Titel | 1 | Ellipsis |
| Karten-/Zeilentitel | 1 | Ellipsis |
| Fixture-Zeile | 1 | Ellipsis |
| Fließtext / Synopsis | 3 | Ellipsis, danach „Mehr" |
| League-Rail | 1 | Ellipsis |
| Tabellenwert | 1 | **niemals** kürzen — Spaltenbreite stattdessen anpassen |

**Nie:** zweizeilige Karten in einer Reihe, die über die Zeile hinauswachsen; Text, der die
Karte vertikal verlässt; `textOverflow = Clip` ohne Ellipsis.

### 6.4 Schriftgewichte

| Gewicht | Name | Einsatz |
|---|---|---|
| 800 | ExtraBold | `display`, `h1` — Zahlen, Hero, Screen-Titel |
| 700 | Bold | `h2`, `h3` — Sektionstitel, League-Rail-Auswahl |
| 600 | SemiBold | `title`, `bodySm`, `label` — Buttons, Chips, Zeilen |
| 500 | Medium | `body`, `micro` — Fließtext |
| 400 | Regular | **nur** lange Synopsis (> 3 Zeilen), nie in der Navigation |

**Regel:** In einer Zeile nie mehr als **zwei** Gewichte. Fokus ändert **nicht** das Gewicht,
sondern die Farbe und den Hintergrund — sonst springt die Zeile beim Zappen.

### 6.5 Zahlen & Tabellen

```
Spielstand        2:1          display, tabular, 800
Spielminute       68′          body, tabular, 600, Akzent
Endstatus         FT  2:1      bodySm, tabular, 700
Tabellenrang      1            body, tabular, 600, Akzent wenn Ausreizer
Tabellenwert      24           body, tabular, 500, muted
Uhrzeit           20:30        h1, tabular, 800
Countdown         1:24:07      h2, tabular, 700
```

Tabellenspalten sind **rechtsbündig** (numerisch), Kopfzeile `label` + `muted`,
Tabellenkopfzeile `dim`, Zellenhöhe 44 dp, Zebra optional nur als `surface1`-Wechsel
(kein Streifen-Farbton).

---

## 7. Layout, Raum & Form

### 7.1 Raster

Die App ist **10-Foot-UI**. Basierend auf 1920 × 1080, gerastert in ein 12-Spalten-Raster
mit 58 px Rand (≈ 32 dp).

| Ebene | Wert (1920 × 1080) | dp |
|---|---|---|
| Seitenrand horizontal | 58 px | 32 |
| Seitenrand vertikal | 44 px | 24 |
| Spalten | 12 | — |
| Spaltensteig | 28 px | 15,5 |
| Inhalts-Start (unter Cluster) | 150 px | 83 |
| Rail-Breite (docked) | 100 px | 56 |
| Kanalzeilen-Höhe | 84 px | 47 |
| Poster-Kachel | 16:9 | — |
| Mindest-Zielgröße (D-Pad) | 48 px | 27 |

### 7.2 Abstandsskala

```
4 · 8 · 16 · 24 · 32 · 48        (dp)
```

| Token | dp | mpx | Einsatz |
|---|---|---|---|
| `space.xs` | 4 | 7 | Icon↔Label, Badge-Innen |
| `space.sm` | 8 | 14 | Chip-Padding, Icon-Box |
| `space.md` | 16 | 29 | Karten-Innenraum, Listenzeile |
| `space.lg` | 24 | 43 | Panel-Padding |
| `space.xl` | 32 | 58 | Seitenrand |
| `space.2xl` | 48 | 86 | Sektionsabstand |

**Regel:** Abstände kommen **nur** aus dieser Skala. Ad-hoc-Werte sind verboten.
Innerhalb einer Zeile: mindestens `space.sm` zwischen zwei Elementen, `space.md` zwischen
Blöcken, `space.xl` zwischen Sektionen.

### 7.3 Radien

| Token | dp | mpx | Einsatz |
|---|---|---|---|
| `radius.sharp` | 3 | 6 | Tag, Status-Punkt |
| `radius.plate` | 7 | 12 | Wappen-Platte, Icon-Tile |
| `radius.poster` | 9 | 16 | Poster-Kachel |
| `radius.row` | 11 | 20 | Listenzeile, Karte |
| `radius.card` | 11 | 20 | Karte |
| `radius.button` | 12 | 22 | Button |
| `radius.sheet` | 18 | 32 | Sheet, Dialog |
| `radius.pill` | ∞ | 17 | Pill, Badge, Segment |

**Regeln:**
- Radien skalieren **proportional** mit dem Element, nicht absolut — eine 12-dp-Karte und eine
  200-dp-Karte teilen sich dieselbe Kurvenform (≈ 22 % der kürzeren Kante).
- Innerhalb einer Verschachtelung: innerer Radius = äußerer Radius − Padding.
- Pill nur für volle Breite oder Pill-Form (Badge, Segment, Playlist-Pill). Keine Pill-Karten.

### 7.4 Elevation

| Stufe | Dark | Light | Einsatz |
|---|---|---|---|
| `e0` | — | — | flach, auf Fläche |
| `e1` | `0 2 8` @ 30 % | `0 2 8` @ 10 % | Karte, Chip |
| `e2` | `0 8 24` @ 38 % | `0 8 24` @ 12 % | Hover, Menü |
| `e3` | `0 24 60` @ 45 % | `0 24 60` @ 16 % | Sheet, Dialog, Fokus |

**Regel:** Auf der 10-Foot-UI trägt **Höhe** (Y-Versatz), nicht Weichheit.
Ein Element, das sich beim Fokussieren nicht bewegt, existiert nicht.

---

## 8. Motion

### 8.1 Dauer & Easing

| Token | Dauer | Easing | Einsatz |
|---|---|---|---|
| `motion.instant` | 0 ms | — | Fokuswechsel bei „Animations = Off" |
| `motion.fast` | 120 ms | `cubic-bezier(0.2, 0, 0, 1)` | Chip, Hover, Toggle |
| `motion.base` | 200 ms | `cubic-bezier(0.2, 0, 0, 1)` | **Standard** für alles Sichtbare |
| `motion.slow` | 320 ms | `cubic-bezier(0, 0, 0, 1)` | Sheet öffnen, Seitenwechsel |

**Standard ist 200 ms.** Alles darüber wirkt auf einem Fernseher träge.

### 8.2 Bewegung

| Element | Bewegung | Dauer |
|---|---|---|
| Fokuswechsel | Ring-Bloom + Scale 1,04 + Glow | 120 ms |
| Karte gedrückt | Scale 0,97 | 80 ms |
| Sheet öffnet | Translate Y 24 px → 0 + Fade | 320 ms |
| Glint (Glas) | Lichtband wandert einmal durch | 320 ms |
| Rail-Expand | Breite + Fade | 200 ms |
| Hero-Wechsel | Cross-Fade 250 ms | 250 ms |
| **LIVE-Puls** | Punkt `0,7 → 1,0`, Opacity, Endlos | 1,4 s Zyklus |
| Spielfortschritt | Linear wachsend | live |

### 8.3 Regeln

- **Alles abschaltbar.** „Animations = Off" ⇒ alle endlichen Tweens 0 ms.
- **Endlose Animationen nie auf 0 ms setzen.** `infiniteRepeatable` mit 0-ms-Dauer ist eine
  Division durch null auf dem Main-Thread. Stattdessen: die Animation **komplett abschalten**
  (den Live-Punkt statisch voll zeichnen).
- Keine Bewegung ohne Zweck. Kein Bounce, kein Overshoot, kein Parallax über den Screen hinaus.
- Fokus-Animation **darf nicht die Zeilenhöhe ändern** — sonst springt das Raster.
- Alles > 320 ms braucht eine Begründung im Code.

---

## 9. Icons

### 9.1 Regeln

| Eigenschaft | Wert |
|---|---|
| Stil | **Line-Icon**, 1,75 px Strich bei 24 px Raster |
| Ecken | round (Radius = halbe Strichstärke) |
| Enden | round |
| Füllung | keine, außer Statuszustand (LIVE-Punkt, gefüllter Stern) |
| Raster | 24 × 24 px, 2 px Padding, 20 px Zeichenfläche |
| Ausrichtung | optisch zentriert, nicht mathematisch |
| Abstand zum Text | `space.sm` (8 dp), vertikal zentriert auf Cap-Height |
| Export | SVG (`stroke-linecap="round"`, `stroke-linejoin="round"`), 1,75 px |

### 9.2 Icon-Typen

```
Linie    Standard (Play, Settings, Search, Chevron, Info)
Zustand  gefüllt, wenn Zustand (Stern gefüllt = gefolgt, REC-Punkt gefüllt)
System   TV-spezifisch (CH+/CH−, D-Pad, Live-Badge, EPG-Raster)
Marke    Logo, Play-Dreieck, Sport-Hub-Piktogramm
```

### 9.3 Abstand zum Fokus

- Fokus-Icon skaliert **nicht** — nur Farbe und Ring.
- Fokus-Icon behält seine Strichstärke. Verdickte Striche bei Fokus lesen sich als Fehler.
- Ein Icon ohne Text braucht einen größeren Ring (+2 px Außenabstand), sonst wirkt es
  instabil. Reine Icon-Kacheln: min. 48 dp.

### 9.4 Semantik der Sport-Icons

| Icon | Bedeutung | Niemals verwechseln mit |
|---|---|---|
| ⚽ Ball + Posaune | **Sport-Hub** | dem generischen Entertainment-Glyph |
| 🔴 pulsierender Punkt | **jetzt live** | Aufnahme, Fehler |
| ⏺ REC (Viereck) | **wird aufgezeichnet** | Live |
| ⏱ Uhr mit Pfeil | **Catch-up / Rückblick** | „heute Abend" |
| 📋 Raster | **Tabelle / Aufstellung** | Guide |
| 📡 Antenne | **TV-Sender-Zuordnung** | Netzwerk-Fehler |
| ⭐ gefüllt | **gefolgt** | Favorit (Herz) |

**Favorit ≠ gefolgt:** Favorit = der Nutzer mag den Kanal (Herz). Gefolgt = der Nutzer
will Ligen-Updates zu einem Team (Stern). Zwei verschiedene Symbole, zwei verschiedene
Datenmodelle, zwei verschiedene Menüs — das ist eine bewusste Trennung.

---

## 10. Komponenten

### 10.1 Fokus (der wichtigste Zustand der App)

```
┌────────────────────────────────────────────────┐
│  Ring: 3 px #81EEB0 (dark) / #0E6B41 (light)  │
│  Glow: accent @ 40 % (dark) / 28 % (light)     │
│  Abstand Ring→Element: 2 px                    │
│  Scale: 1.04                                    │
│  Dauer: 120 ms                                 │
└────────────────────────────────────────────────┘
```

| Zustand | Ring | Füllung | Text |
|---|---|---|---|
| **Fokus** | 3 px Akzent | Akzent voll | `onPrimary` |
| Ausgewählt, kein Fokus | 1 px `outline` | Akzent @ 14 % | Akzent |
| Normal | keine | `surface1` | `text` |
| Disabled | keine | transparent | `dim`, kein Fokus |

**Regeln:**
- **Es gibt immer genau einen Fokus.** Kein „hover", kein „selected ohne Fokus" als
  zweiter Zustand — Selected ist eine Eigenschaft, Fokus ist eine Position.
- Fokusring ist **nie** die einzige Änderung: Fokus ändert immer auch Füllung oder Textfarbe.
- Der Fokus springt **nicht** aus dem Bild: bei einem Wechsel weiter als eine Seite
  wird die Zeile erst am Ende eingeblendet (`fade` über 200 ms), nie während des Scrollens.
- Fokus-Zurück über `Right` landet **exakt** auf dem Element, von dem man weggegangen ist.

### 10.2 Buttons

```
Primär        Füllung: Akzent · Text: onPrimary · Radius 12 dp · Höhe 44 dp
Sekundär      Füllung: surface2 · Text: text · 1 px outline · Höhe 44 dp
Tertiär       Keine Füllung · Text: Akzent · Höhe 40 dp
Destruktiv    Füllung: transparent · Text: error · 1 px error @ 40 %
```

| Eigenschaft | Wert |
|---|---|
| Höhe | 44 dp (Primär/Sekundär) · 40 dp (Tertiär) |
| Padding horizontal | 22 dp |
| Label | `bodySm` (9,4 sp), 600 |
| Icon | optional, 20 px, 8 dp Abstand zum Text |
| Fokus | §10.1, zusätzlich 2 px Außenabstand |
| Disabled | Text + Icon `dim`, keine Füllung, **kein Fokus** |

Primär-Buttons: **maximal einer pro Screen.** Zwei konkurrierende Primärbuttons sind ein
Designfehler, kein Feature.

### 10.3 Fixture-Zeile (Kernkomponente des Sport-Hubs)

```
┌────────────────────────────────────────────────────────────────────────────┐
│ ● 20:30  BAY  2 : 1  BVB      Sky Sport 1, DAZN            ⭐            │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │
└────────────────────────────────────────────────────────────────────────────┘
   ↑     ↑        ↑                ↑                                      ↑
 Status Zeit Teams + Score        Sender (matched)                Gefolgt
```

| Element | Wert | Zeile |
|---|---|---|
| Status-Indikator | 8 dp Punkt, `LIVE` wenn live | 1 |
| Uhrzeit | `label`, tabular | 1 |
| Teams | `title`, Kürzel, 1:1 | 1 |
| Spielstand | `body` 700, tabular, Akzent wenn live | 1 |
| Fortschritt | 3 dp hoch, `accentCont` | 2 |
| Sender | `label`, `muted` | 1 |
| Gefolgt | Stern 20 px, `accent` gefüllt wenn gefolgt | 1 |

**Live-Zeile:** Status-Punkt pulsiert, Spielstand in Akzent, `LIVE`-Wort gesetzt.
**Nicht-live:** Punkt `dim`, Spielstand in `text`, kein Wort.
**Fokus:** Zeile füllt sich komplett mit Akzent, Text wird `onPrimary`, Ring nach §10.1.

### 10.4 League-Rail

Vertikal, links. 16 Ligen. Auswahl und Fokus sind getrennt:

| Zustand | Hintergrund | Text |
|---|---|---|
| Fokus | Akzent voll | `onPrimary` |
| Ausgewählt | Akzent @ 14 % | Akzent |
| Normal | `surface1` | `text` |
| Gefolgt | `surface1` + Stern rechts | `text` |

Beim Fokus **kein Aufklappen** der Rail — die League-Namen bleiben 20 px, die Rail wächst nicht.
Das Layout bleibt stabil, das Raster springt nicht.

### 10.5 Chips & Badges

| Typ | Höhe | Radius | Verwendung |
|---|---|---|---|
| Chip | 28 dp | Pill | Filter, League-Tags, Sender |
| Badge | 20 dp | `radius.sharp` | Zähler auf Rail-Item |
| Status-Badge | 20 dp | `radius.sharp` | `LIVE`, `REC`, `4K`, `HDR` |
| Provider-Chip | 24 dp | `radius.plate` | Playlist-Zuordnung |

- Badge-Zahl: `label` 700 in Akzent-Fläche, Text `onPrimary`.
- `LIVE`-Badge: **rot gefüllt** + Punkt + Wort. Nie nur rot gefüllt.
- Chip: `surface2` + 1 px `outlineVar`; Fokus wie Button.
- Max. 6 sichtbare Chips pro Zeile, Rest als `+N`.

### 10.6 Tabellen (Standings)

```
Pos  Team              P    W    D    L    Pts
─────────────────────────────────────────────────
 1   FC Bayern          26   18   5    3     59
 2   BVB 09            26   17   4    5     55
```

| Eigenschaft | Wert |
|---|---|
| Zeilenhöhe | 44 dp |
| Kopfzeile | `label`, `dim`, sticky, `surface1` |
| Zahlenspalten | rechtsbündig, `body` tabular 500, `muted` |
| Teamname | `title` 600, linksbündig, Ellipsis nach 1 Zeile |
| Rang | `body` 600 tabular; Top 4 in Akzent; Absteig (Relegation) mit ↓-Glyph |
| Trennlinien | 1 px `outlineVar`, keine Zebra |
| Ausreizerzeile | 2 px Akzent-Linie links + `primaryCont`-Hintergrund |

### 10.7 Leerzustände (§4.5)

```
┌──────────────────────────────────────────┐
│                                          │
│              ⚽  No games today           │
│                                          │
│     The Bundesliga has no fixtures on    │
│     Tuesday. Check back tomorrow.        │
│                                          │
│           [ Show other leagues ]         │
│                                          │
└──────────────────────────────────────────┘
```

Icon 48 dp `dim` · Titel `h3` `text` · Text `body` `muted`, max 60 Zeichen/Zeile, max 3 Zeilen ·
Button `Tertiär`, nur wenn eine Aktion existiert. Kein Icon → kein Rahmen, kein Schatten:
Leerzustände sitzen auf der Seite, nicht in einer Karte.

### 10.8 Player-HUD

| Element | Farbe | Regel |
|---|---|---|
| Seek-Bar | Akzent (`accentOnVideo`) | Fetteste Stelle des Bildes |
| Play/Pause | Weiß, 48 dp | Größtes Ziel |
| Tool-Buttons | `white` @ 90 % | 20 px Icons |
| Panels | `rgba(0,0,0,0.55)` | `glassTint` bei 62 % |
| Text über Video | **Immer Weiß**, auch im Light-Theme | Nie `#081C11` über Video |
| Untertitel | Weiß mit `scrim` | Unveränderlich |

**Regel:** Über Video wird **kein** Light-Theme-Recoloring angewendet. Text auf Videobild
ist in beiden Themes weiß — der einzige Fall, in dem `hudInk` nicht dem Theme folgt.

### 10.9 Rail & Navigation

| Element | Wert |
|---|---|
| Breite (docked) | 100 px, floating: 56 px Kapsel |
| Logo oben | `BrandLockup`, 36 dp Marke, Wortmarke 2,75 × Markenbreite |
| Avatar unten | 36 dp, Kreis, `surface4`-Rand |
| Icons | 24 px, `navText`, Fokus → Akzent voll |
| Beschriftung | 20 px (10,2 sp), nur bei `Widen on focus` |
| „Now playing" | 5 dp Akzent-Punkt + Gerätetitel, `micro` |

Ein Punkt in der Rail (unread, laufendes Bild) ist **Akzent**, nie Rot.

### 10.10 Eingabefelder & Tastatur

| Eigenschaft | Wert |
|---|---|
| Höhe | 48 dp |
| Radius | `radius.row` (11 dp) |
| Ruhe | `surface1` + 1 px `outline` |
| Fokus | 2 px Akzent-Ring + `surface2` |
| Text | `title`, `text` |
| Platzhalter | `dim` |
| Fehler | 1 px `error` + Fehlertext `micro` darunter, nie nur rot |

Mit Fernbedienung: **Groß/Klein**-Tasten ändern, **OK** bestätigt, **Back** verwirft die
uncommitteten Änderungen und behält den alten Wert.

---

## 11. Sport-Spezifika

### 11.1 Live-Indikator

Das wichtigste Signal der App. Es muss aus 3 Metern Entfernung, bei Bewegung, sofort lesbar sein.

```
Zustand       Darstellung
─────────────────────────────────────────────────────────────
LIVE          ● pulsierend (1,4 s)  ·  "LIVE"  ·  Rot
LIVE + Min.   ● pulsierend  ·  "LIVE · 68′"  ·  Rot
Geplant       ○  ·  "20:30"  ·  `dim`
Beendet       ✓  ·  "FT 2:1"  ·  `muted`
Verschoben    "⚠ Postponed"  ·  Amber
Unbekannt     "– 20:30"  ·  `dim`
```

**Regeln:**
- **LIVE ist nie stumm.** Immer Punkt **und** Wort. Der Punkt pulsiert nur bei LIVE.
- **Der Puls ist die einzige Endlos-Animation der App.** Alles andere ist endlich.
- LIVE nur bei `FixtureState.LIVE`. Nie bei „gleich" oder „gerade zu Ende".
- Bei Catch-up/Wiedergabe: **kein** LIVE-Badge, sondern `Catch-up`-Chip + Zeitstempel
  („gestern 21:05"). Sonst denkt der Nutzer, das sei gerade jetzt.

### 11.2 Spielstand

```
Heim  2 : 1  Gast
```

| Eigenschaft | Wert |
|---|---|
| Schrift | `display` (25,6 sp) 800, **tabulare Ziffern** |
| Trenner | `:` (Doppelpunkt) |
| Farbe (live) | Akzent |
| Farbe (beendet) | `text` |
| Farbe (verschoben) | `dim` + Wort `Postponed` statt Zahlen |
| Animation | Ziffernwechsel `motion.fast`, **keine** Zähl-Animation |
| Einzelpunkte | Nur bei Spielarten, die sie haben (Tennis, American Football) |

**Bei eigenen Toren/Punkten:** Ein Wechsel der Ziffer bekommt 200 ms Akzent-Hinterlegung.
Nur einmal — nie loopend, das ist eine Daueransage.

### 11.3 Tabellen & Aufstellungen

Siehe §10.6. Zusätzlich:

- **Spalten** sind **nicht fest**, sie kommen aus der API. Immer horizontal scrollbar,
  Rank + Team bleiben **gepinnt** links.
- **Absteiger** (Relegation) und **Europaplatz** erhalten eine eigene Glyphe (↓ / ●),
  nie nur eine Farbe.
- Keine Tabelle ohne Mindestensangabe der Quelle. `Stand: 12. Oct, 20:15` in `micro` darunter.

### 11.4 Kanal-Mapping

Das eigentliche Alleinstellungsmerkmal: ESPN-Fixture → eigener Sender.

```
┌────────────────────────────────────────────────┐
│  Channels                              3       │
│ ─────────────────────────────────────────────  │
│  ▣  Sky Sport Bundesliga 1      [ PLAY ]     │
│     "Bundesliga: Bayern – Dortmund"            │
│  ▣  DAZN                                     │
│     "Bayern Munich v Dortmund"                │
│  ▣  Sport1                                    │
└────────────────────────────────────────────────┘
```

| Element | Wert |
|---|---|
| Match-Confidence | Nur `"Matched via guide"`, `"Matched by name"`, `"Possible match"` |
| Confidence unsicher | `dim` statt `text` + Hinweis-Icon |
| Kein Match | Leerzustand nach §4.5 — **kein** Ergebnis erzwingen |
| Play-Button | Sekundär, auf der Zeile, nicht in der Zeile |

**Wichtig:** Ein unsicherer Match wird **als unsicher markiert**, nie still angezeigt.
Falsch-positive Kanalzuordnungen sind bei Live-TV der teuerste Fehler der App.

### 11.5 Sport-Hub-Struktur

```
League-Rail │ Fixture-Liste (Live / Heute / Demnächst / Ergebnisse)
            │ Detail-Pane (Spielstand · Spielort · TV-Sender · Tabelle · Kanäle)
```

| Regel | Wert |
|---|---|
| Standard-Gruppe | **Live** (falls Spiele laufen), sonst **Heute** |
| Max. Höhe einer Liste | Der Detail-Pane bestimmt sie, nicht die Liste |
| Scrollen | Eine Liste gleichzeitig, nie zwei |
| Fokus-Einstieg | Erster Live-Fixture, sonst erster der ersten Gruppe, sonst League-Rail |
| League ohne Spiele | Rendert leere Sektion, **nie** einen Fehler |

### 11.6 Sport-Icons & Wappen

| Situation | Darstellung |
|---|---|
| Wappen vorhanden | 40 dp Platte, `radius.plate`, `surface2` hinter transparentem PNG |
| Wappen fehlt | 40 dp Platte mit **Kürzel** (`title` 700, `muted`) |
| Wappen zu dunkel | `surface3`-Platte + 1 px `outline` |
| Mehrere Sender | Waagerecht, max. 4 sichtbar, Rest `+N` |

---

## 12. Barrierefreiheit

### 12.1 Farbe ist nie die einzige Information

Jede Information muss **mindestens zwei** Kanäle haben:

| Information | Farbe | + | Form/Text |
|---|---|---|---|
| Live | Rot | + | pulsierender Punkt + „LIVE" |
| Aufnahme | Rot-Magenta | + | REC-Glyphe + „REC" |
| Gewonnen/verloren | gedeckt | + | fett bzw. normal + `W`/`L` |
| Absteiger | Rot-Glyphe | + | ↓-Zeichen + Kürzel |
| Gefolgt | Akzent-Stern | + | gefüllter Stern |
| Ausgewählt | Akzent-Fläche | + | Check-Glyphe |

**Deuteranopie-Prüfung:** Der Prüflauf zeigte, dass Akzent-Grün und Live-Rot bei
Deuteranopie auf 2,13:1 fallen — unterscheidbar, aber knapp. Bei **Rot** vs. **Orange** fällt
es auf 1,9:1: **deshalb nie Rot und Orange nebeneinander** für verschiedene Bedeutung.

### 12.2 Kontrast

| Ebene | Anforderung | Status |
|---|---|---|
| Fließtext, Spielstand, Button-Label | ≥ 4,5:1 | ✓ alle geprüft |
| Sekundärtext, Metazeile, Tab | ≥ 4,5:1 | ✓ alle geprüft |
| Disabled-Text | ≥ 3:1 | ✓ |
| Fokusring, Icons, Rahmen | ≥ 3:1 | ✓ |
| Großer Text (≥ 18,66 sp / 24 px) | ≥ 3:1 | ✓ |
| Text auf Video | Weiß + Scrim ≥ 55 % | ✓ |

### 12.3 Textgröße & Skalierung

| Einstellung | Bereich | Default |
|---|---|---|
| UI-Zoom (Geometrie) | 60 – 140 %, 5-%-Schritte | **90 %** |
| Schriftgröße (Text) | 60 – 140 %, 5-%-Schritte | 100 % |
| Popup-Schriftgröße | 50 – 120 % | 100 % |
| Popup-Größe | 50 – 120 % | 100 % |

- Die **System-Skalierung** bleibt die Basis und wird multipliziert, nicht ersetzt.
- Bei 140 % Textskalierung darf **keine** Zeile abgeschnitten werden — Layout bricht um,
  es wird nie gekürzt.
- Die Einstellung ist im **ersten Setup-Schritt** sichtbar, mit Live-Vorschau, damit der
  Nutzer sie vom Sofa aus beurteilt.
- Prüf-Locales: **Arabisch** (RTL, höhere Zeilenhöhe), **Thai**, **Finnisch** (lange Wörter).

### 12.4 Bewegung

- Vollständig abschaltbar (§8.3).
- Kein Auto-Playing von Bewegung ohne Fokus.
- Keine Animation an UI-Elementen, die bei `reduce motion` als kritisch eingestuft sind
  (Score-Wechsel, Live-Puls sind die Ausnahme — sie tragen Information).

### 12.5 D-Pad & Fernbedienung

| Anforderung | Wert |
|---|---|
| Alles per D-Pad erreichbar | **Pflicht** — kein Touch-only-Feature |
| Zielgröße | ≥ 48 px (27 dp) |
| Fokus-Sprungweite | max. 1 Element pro Tastendruck |
| Keine Fokusfalle | `Back` verlässt jede Zone |
| Raster-Sprung | Channels 100 (10 Seiten) · EPG 30 min · League-Rail 3 |

### 12.6 Sprache & Screenreader

- Jedes interaktive Element hat ein `contentDescription`.
- Icon + Text werden als **ein** Label gelesen: „Play, Bayern gegen Dortmund, live".
- Zahlen werden mit Einheiten vorgelesen (`2 zu 1`, nicht `2:1`).
- Proper Nouns bleiben unübersetzt — auch in Screenreader-Labels.
- 27 Sprachen; RTL (Arabisch, Hebräisch, Farsi) ist unterstützt und muss bei jedem
  Layout-umbau geprüft werden.

---

## 13. Implementierung in der App

### 13.1 Dateien, die die Farbe speisen

| Datei | Rolle |
|---|---|
| `OwnTV_Core-main/core/src/main/java/tv/own/owntv/core/theme/Palette.kt` | **Die** Farbwerte (`OwnTVPalette`) — hier stehen alle Hex-Codes |
| `app/src/main/java/tv/own/owntv/ui/theme/Color.kt` | Wickelt Core-Werte in Compose-`Color` |
| `app/src/main/java/tv/own/owntv/ui/theme/OwnTVColors.kt` | Löst Theme + Accent zu M3-Rollen auf |
| `app/src/main/java/tv/own/owntv/ui/theme/StageTokens.kt` | Stage-Neutrals, Radien, `stageText()` |
| `app/src/main/java/tv/own/owntv/ui/theme/AccentColor.kt` | Accent-Preset → Label |
| `app/src/main/java/tv/own/owntv/features/sport/**` | Sports-Hub, Teamfarben |

### 13.2 Umstellung auf Sportio-Grün

**Schritt 1 — Palette ersetzen.** `SportioPalette.kt` aus `docs/brand/` nach
`OwnTV_Core-main/core/src/main/java/tv/own/owntv/core/theme/` kopieren.

**Schritt 2 — Accent-Preset.** In `Palette.kt` den `TealPalette`-Block durch einen
`Sportio`-Eintrag ersetzen. Statt fünf Presets (TEAL/BLUE/VIOLET/GREEN/AMBER) nur noch
**`SPORTIO`** in `AccentColor.kt`:

```kotlin
enum class AccentColor { SPORTIO }
fun AccentColor.roles(isDark: Boolean) = SportioPalette.roles(isDark)
```

> **Warum die Presets weg?** Ein TV-Player hat keinen Platz für fünf Marken-Akzente.
> Die Marke ist Grün. Der Nutzer darf weiterhin einen **freien Hex-Akzent** wählen
> (Fokus-Highlight ja, Marken-Akzent nein) — das bleibt unverändert.

**Schritt 3 — Logo-Assets.** Die Wortmarke ist **kein Upstream-Asset** und wird nicht
nachgezeichnet, sondern neu gesetzt: `tools/brand/SportioLogo.java` liest dieselbe
Variable-Font-Datei, die die App rendert, und schreibt die Glyphen als Vektorpfade
(§2.8). Die fertigen Dateien liegen in `assets/` und werden nur noch verdrahtet:
`assets/android/sportio_mark.xml` + `sportio_wordmark.xml` nach `res/drawable`,
dann `BrandLockup.kt` und `AppIcon.kt` auf `sportio_*` umstellen.

**Schritt 4 — Teamfarben.** In `SportsScreen.kt` die Vereinsfarben über
`SportioPalette.Team` + `teamColor()` auflösen statt der Roh-`colorHex`.

**Schritt 5 — Stage-Neutralen.** `StagePalette.Dark` / `.Light` in `StageTokens.kt` auf
`SportioPalette.Stage` umstellen. Vier Light-Werte weichen dabei vom Upstream ab (§5.5).

**Schritt 6 — Semantik prüfen.** `StageColors.Ok` / `.Warn` / `.Danger` auf die
Semantik-Tabelle aus §5.6 umstellen.

### 13.3 Doku-Matrix: wo steht welcher Wert?

Der Guide beschreibt mehr Werte, als eine Datei fassen kann. Diese Zuordnung ist verbindlich —
ein Wert ohne Codepunkt ist ein toter Token.

| Ebene | Quelle im Code | Token-Datei | Guide |
|---|---|---|---|
| M3-Rollen, Rampe, Semantik, Teamfarben | `SportioPalette.kt` (Core) | `sportio.tokens.json` → `color.dark/light/ramp/status/team` | §5.1–§5.7 |
| Stage-Neutralen (`dim`, `body`, `navText`) | `StageTokens.kt` (App) | `…tokens.json` → `color.stage` | §5.5 |
| Glas, Motion, UiZoom | `Glass.kt`, `AnimationLevel.kt` (Core) | `…tokens.json` → `motion` | §5.9, §8 |
| Wortmarke, Marke, Lockup, Banner | `tools/brand/SportioLogo.java` | — | §2 |
| Typografie-Skala | `Type.kt`, `StageTokens.kt` | `…tokens.json` → `font` | §6 |
| Abstände, Radien | `Dimens.kt`, `StageRadii` | `…tokens.json` → `space`, `radius` | §7 |

**Regel:** Eine Änderung an einer Farbe ändert **Guide + Kotlin + Tokens** — sonst greift
`tools/brand/audit_brand.py` (§13.5) und meldet Drift.

### 13.3 Werte-Referenz (Copy-Paste-Block)

```kotlin
// Sportio — Core/theme/SportioPalette.kt (Auszug, vollständig in docs/brand/)
const val Pitch = 0xFF4FEE94L      // Signatur (dark)
const val PitchDeep = 0xFF0E6B41L  // Signatur (light)
const val Beam = 0xFF81EEB0L       // Fokusring (dark)
const val Field = 0xFF134D2CL      // Accent-Container (dark)

// Dark
const val DarkBackground = 0xFF060B07L
const val DarkOnSurface  = 0xFFEAF7EFL
const val DarkPrimary    = 0xFF4FEE94L

// Light
const val LightBackground = 0xFFF4F8F4L
const val LightOnSurface  = 0xFF081C11L
const val LightPrimary    = 0xFF0E6B41L

// Semantik
const val LiveDark = 0xFFFF5A5FL; const val LiveLight = 0xFFC42127L
const val RecordDark = 0xFFFF3B5CL
const val WarnDark = 0xFFFFB020L;  const val WarnLight = 0xFF7A4D00L

// Stage (eigene Schicht, in StageTokens.kt, nicht in der M3-Palette)
SportioPalette.Stage.Dark.Dim   // 0xFF7E9C88   6.61:1
SportioPalette.Stage.Light.Dim  // 0xFF5E7566   4.65:1
SportioPalette.Stage.Light.Ok   // 0xFF1B743C   war #2B8A3E (4.08:1)
```

### 13.4 Was **nicht** geändert wird
- Die Icon-Menge und ihr Raster.
- Die Typografie-Skala (nur die Familie ist weiterhin Plus Jakarta Sans — sie passt).
- Der User-Akzent-Hex (bleibt, ist aber nur noch für den **Fokus-Highlight** gedacht).
- GPLv3-Lizenz und Upstream-Danksagung.

### 13.5 Konsistenz prüfen

Der Guide hat zwei maschinenlesbare Zwillinge, und genau daran rottet eine Marke: der Prosa-Teil
nennt Farben, die der Code nicht kennt, oder umgekehrt. Drei Skripte in `tools/brand/` fangen das ab —
sie laufen ohne Fremdbibliothek auf dem portablen Python in `tools-setup/python-embed`.

```powershell
$py = "C:\Users\<user>\OwnTV\tools-setup\python-embed\python.exe"

& $py tools\brand\audit_brand.py          # Guide vs Tokens vs Kotlin, Rampe aus der Farbmaths
& $py tools\brand\check_contrast.py        # Stage-Neutralen gegen Seite UND gehobene Fläche
& $py tools\brand\check_svg_bounds.py      # Logo-SVGs: liegt die Grafik in ihrem viewBox?
```

`audit_brand.py` rechnet die Grün-Rampe aus dem HSL-Satz neu (`hue 146`) und vergleicht sie
Token für Token gegen Guide, JSON und Kotlin. **Exit 1 bei Drift.** Der erste Lauf fand drei echte
Fehler: `P90` als `#105D2C` in zwei von drei Dateien, zwei konkurrierende Rampen-Schlüssel
(`50…1000` vs. `P0…P100`) und ein `dim`, das im Token-Set existierte, aber in keiner
Kotlin-Rolle — weil M3 diese Rolle nicht kennt.

`SportioLogo.java` prüft sich beim Bauen selbst: es misst die Tinte jedes exportierten PNG gegen
den Bildrand und beendet sich mit Code 1, wenn etwas beschnitten ist (Exit 1 kam zuletzt daher,
dass die Wortmarke im Raster um 167 px zu tief saß).

---

## 14. Checklisten

### 14.1 Neues Screen-Element

- [ ] Farbe aus dem Token-Set, kein Hex-Literal im Code
- [ ] Kontrast ≥ 4,5:1 (Text) bzw. ≥ 3:1 (UI) gegen den **tatsächlichen** Träger
- [ ] Schrift aus der Skala, kein freier `sp`-Wert
- [ ] Abstände aus `space`, Radius aus `radius`
- [ ] D-Pad-erreichbar, Ziel ≥ 48 px, Fokusring nach §10.1
- [ ] Fokus ändert auch Füllung oder Textfarbe, nicht nur den Ring
- [ ] Kein Text über 3 Zeilen ohne „Mehr"
- [ ] Leerzustand nach §4.5, Fehlerzustand nach §4.6
- [ ] Information nicht nur farbcodiert (§12.1)
- [ ] Beide Themes geprüft, inkl. Fokus und Disabled
- [ ] RTL und skaliertes Text geprüft (140 %)

### 14.2 Neue Sport-Funktion

- [ ] Zeigt **Live** nur bei `FixtureState.LIVE`
- [ ] Live-Indikator = Punkt **+** Wort, nie nur Farbe
- [ ] Kanal-Match mit Confidence markiert, unsichere Treffer gekennzeichnet
- [ ] Spielstand tabulare Ziffern, lokalisierte Zeit
- [ ] Kein Ergebnis erzwungen — leer ist ein gültiger Zustand
- [ ] Teamfarben über `SportioPalette.Team`, nie die Roh-`colorHex`
- [ ] Proper Nouns bleiben unübersetzt
- [ ] Keine neue Endlos-Animation außer dem LIVE-Puls

### 14.3 Neue Sprache

- [ ] Proper Nouns von der Übersetzung ausgenommen
- [ ] Platzhalter, Pluralformen und Zahlenformat korrekt
- [ ] Mindestlänge um 20 % gegenüber EN geprüft
- [ ] `micro` (7,8 sp) bricht nicht — besonders Arabisch und Thai
- [ ] Kein Text-Overflow bei 140 % Skalierung
- [ ] Screenreader-Labels mitgeschickt

### 14.4 Release

- [ ] Logo in beiden Themes auf ≥ 32 px Versalhöhe
- [ ] Marke ohne Glanz unter 72 px (`assets/png/sportio-mark-{48,72,96,192,512}.png`)
- [ ] Splash zeigt das aktuelle Lockup, nicht die nackte Marke
- [ ] `assets/android/sportio_mark.xml` in `res/drawable` und in `AppIcon.kt` verdrahtet
- [ ] Kein Upstream-Wortmarken-Asset im APK
- [ ] TV-Banner 320 × 180 im Play-Store-Kanal hinterlegt
- [ ] Store-Listing: Screenshots in **Dark** und **Light**
- [ ] Feature-Graphic 1024 × 500 mit Pitch-Verlauf
- [ ] Kurzbeschreibung nennt **TV** und **Sport** (§3.2)
- [ ] Kein Content-/Provider-Claim im Listing (rechtlich)

---

## Anhang A — Farbreferenz auf einen Blick

**Dark (Standard)**

```
#060B07  #0B120D  #101A13  #16241B  #1E3025  #28402F   Flächen
#3C5A48  #1E3126                                          Rahmen
#EAF7EF  #A6C3AF  #7E9C88                                 Text
#4FEE94  #81EEB0  #30E880                                 Akzent
#134D2C  #9BF5BE                                          Container
#FF5A5F  #FF3B5C  #FFB020  #3FD0E8  #FF8A86               Status
```

**Light**

```
#F4F8F4  #FFFFFF  #EFF5F0  #EDF3EE  #E4ECE6  #DCE6DF   Flächen
#A8BBAE  #D2DED5                                          Rahmen
#081C11  #3C5546  #5E7566                                 Text
#0E6B41  #147B41  #19CC67                                 Akzent
#C8EFD8  #08301C                                          Container
#C42127  #C81038  #7A4D00  #0E6B7D  #C42127               Status
```

**Grün-Rampe**

```
#F3FCF7  #DEF8E9  #B5F2D0  #81EEB0  #4FEE94  #30E880
#19CC67  #16A252  #147B41  #10502C  #0C311C  #081C11
```

**Stage-Neutralen** (§5.5 — eigene Schicht, nicht M3)

```
Dark    text #F1F6F3  muted #A9B8AF  dim  #7E9C88  item #D8E2DC
        menu #DEE7E1  tag   #C6D3CC  body #CBD6D0  nav  #BCCBC2
        ok   #7BE3A4  warn  #FFB86B  danger #FF8A7A  star #FFCC4D
Light   text #0F1A16  muted #4C5A54  dim  #5E7566  item #1C2824
        menu #1C2824  tag   #3D4B45  body #2D3A35  nav  #55635D
        ok   #1B743C  warn  #8B5904  danger #BD2C1F  star #906A09
```

## Anhang B — Logo-Textbausteine

```
Sportio          Wortmarke
SPORTS           Submarke / Kategorie
SPORTIO.TV       Website
@SportioTV       Social-Handle
sportio          Paketname, Dateiname
TV-Sender        „Sportio auf deinem Fernseher"
```

## Anhang C — Glossar

| Begriff | Bedeutung |
|---|---|
| **Fixture** | Ein einzelnes Spiel mit festem Termin |
| **Scoreboard** | Die Liste aller Spiele einer Liga an einem Tag |
| **Live** | Das Spiel läuft gerade |
| **Catch-up** | Eine bereits ausgestrahlte Sendung nachträglich ansehen |
| **Guide / EPG** | Elektronisches Programm, die Sendeplan-Daten |
| **Kanal-Match** | Zuordnung ESPN-Spiel → eigener TV-Sender |
| **Stage** | Das Designsystem dieser App (Rail + Panels + Fokus) |
| **Follow** | Nutzer folgt einem Team für Updates (Stern) |
| **Favourite** | Nutzer mag einen Kanal (Herz) — etwas anderes als Follow |

---

*Sportio Brand Guide v1.0 · greens validated 2026-10-12 ·
Token-Referenz: [`sportio.tokens.json`](./sportio.tokens.json) ·
Code: [`SportioPalette.kt`](./SportioPalette.kt)*