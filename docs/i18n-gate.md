# docs/i18n-gate.md — operativer i18n-Workflow (Stand: 2026-10-13)

> Zweck: Gate-Befehle und Literal-Workflow an einem Ort. Authoring-Guide: `i18n.md`.
> Historie liegt in `archive/`.

## Gate (Pflicht)

```powershell
C:\Users\<user>\OwnTV\tools-setup\python-embed\python.exe tools/i18n/check_hardcoded_strings.py verify --bootstrap
```

- Jeweils im App-Repo (`C:\Users\<user>\OwnTV`) UND im Core-Repo
  (`C:\Users\<user>\OwnTV\OwnTV_Core-main`) ausführen.
- Grössenordnung: App `45+1594` / Core `249+5248` — Zahl im Fehlerfall mit der
  gemeldeten Zahl vergleichen, nicht mit dieser.
- Gradle-Task `verifyI18nLiterals` läuft bei jedem Build mit; ohne Python
  warnt sie nur (CI enforced trotzdem).

## Neues Literal

- UI-Text → `res/values/strings.xml` + `res/values-de/strings.xml` als `sport_*`
  (App) bzw. Core-`strings.xml` (+ de).
- Technisches (json/uri/protocol/technical/log/regex/preference-key/file-path/
  mime/empty) → `classify-safe --path <rel.Pfad> --text '<exakt>' --category …`.
- Gelöschte Literale → `prune-safe` (räumt stale safe-Einträge + Baseline auf).
- Normalisierung beachten: Scanner kollabiert Whitespace (`", "` → `","`);
  Counts müssen exakt stimmen; Backslashes im File doppelt (`\\s+`).
- Apostroph in `donottranslate.xml` als `\'` escapen.

## Overflow-Regel (CI-Gate)

Jedes `Text(..., maxLines = N)` braucht `overflow = TextOverflow.Ellipsis`.
Check: `tools/i18n/check_text_overflow.py`.

## Referenzen

- `internationalization.md` — Gesamtplan v2 (Phasen 0–3 done).
- `i18n.md` — Authoring-Guide (Keys vs. Labels, nie Vergleichs-/Protokollstrings übersetzen).
- App packt nur `tools/i18n/locales.json`-Einträge mit `packaged = true`
  (`resourceQualifier`-Feld); Debug zusätzlich Pseudolocales en-rXA/ar-rXB.
