# HANDOFF — nur Status-Zeiger (Stand: 2026-10-13)

> Diese Datei ist kein Wissensspeicher mehr. Verbindlich: `AGENTS.md` + `docs/INDEX.md`.
> Neues Wissen gehört nach `docs/`, nie hierher.

## Wo was steht

- Regeln/Gates/Verbote: `AGENTS.md`
- Doku-Landkarte: `docs/INDEX.md`
- Bauen/Emulator/Box: `docs/BUILD.md`
- Architektur/Core-Fork/TV-only: `docs/ARCHITECTURE.md`
- Naming-Freeze: `docs/BRANDING.md`
- Sport-Hub + Matching: `docs/SPORT-HUB.md` (Details: `sport-hub-plan.md`, `sport-channel-matching.md`)
- i18n: `docs/i18n-gate.md` (Guide: `docs/i18n.md`, Plan: `docs/internationalization.md`)
- Release/Signing: `docs/RELEASE.md`

## Status

- Sport-Hub Phasen 1–7 fertig (2026-10-12). App v1.0.1 / Core 1.0.68 released.
- v1.0.2 (2026-10-13): released — CI success, APKs live. Lokaler Core-Pin → 1.0.68 mitgezogen.
- v1.0.3 (2026-10-13): released — CI success, APKs live, Matching in APK verifiziert (sports_matching.json + Sprachfilter-Keys im Dex).
- Doku (2026-10-13): Release-Kette + Fork-Schutz + API-Fallen nach `docs/RELEASE.md`, Downgrade/Box-Ports nach `docs/BUILD.md`. Leak-Audit beider Repos: keine Secrets/Credentials/Keystores; einziger Fund: private Mail in API-Commit-Autoren — künftig Noreply + Private-Email-Setting.
- Umzug auf `trenntl` (2026-10-13, Privacy): beide Repos per Squash (Noreply) kopiert, Referenzen umgestellt, Core 1.0.69 neu publisht, App v1.0.5 live. Bridge v1.0.4 im alten Repo leitet Updater um. Offen: alte Repos archivieren/löschen, Upstream-PRs (#254/#30) schließen.
- Handy-Verwaltung (Companion `/select`) seit 2026-10-13 dreistufig: Start (aktive Ligen
  mit Zähler) → Liga mit Tabs Teams/Spieler → „Alle verwalten" (Region-Akkordeons, Suche);
  ein Submit. Details: `docs/sport-hub-plan.md` §7.7.
- Arbeitsstand: Ordner hängt an `trenntl/SportioTV` (Backup-Branches vorhanden), Core frisch geklont (Build grün), READMEs + User Guide als Sportio neu, Naming-Freeze in `docs/BRANDING.md`.
