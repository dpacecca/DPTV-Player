# HANDOFF — nur Status-Zeiger (Stand: 2026-10-14)

> Diese Datei ist kein Wissensspeicher. Verbindlich: `AGENTS.md` + `docs/INDEX.md`.
> Neues Wissen gehört nach `docs/`, nie hierher.

## Wo was steht

- Regeln/Gates/Verbote: `AGENTS.md`
- Doku-Landkarte: `docs/INDEX.md`
- Bauen/Emulator/Box: `docs/BUILD.md`
- Architektur: `docs/ARCHITECTURE.md`
- Naming-Freeze + Brand: `docs/BRANDING.md`
- Sport-Hub + Matching: `docs/SPORTS_HUB.md` (Details: `sport-hub-plan.md`, `sport-channel-matching.md`)
- i18n: `docs/i18n-gate.md`
- Release/Signing: `docs/RELEASE.md`

## Status

- 2026-10-14: DPTV-Player als eigenständiges Projekt aufgesetzt (Repo `dpacecca/DPTV-Player`).
  Core-Bibliothek liegt in `core-lib/` (Composite-Build, kein externes Maven mehr ausser libmpv).
  Rugby Union (25 Wettbewerbe) + Rugby League (NRL) im Sport-Hub. Eigenes Icon/Banner/Wordmark
  aus `tools/brand/make_dptv_brand.py`. Noch kein Release, kein eigener Signing-Keystore.
