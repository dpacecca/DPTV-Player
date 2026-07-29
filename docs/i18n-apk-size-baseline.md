# i18n locale-filter APK-size baseline

> Recorded at Phase 0 completion (commit on `feature/i18n-phase-0`, 2026-07-29) as the reference for
> measuring the resource-table cost of packaging additional locales in Phase 4.

## Why this exists

`androidResources.localeFilters` strips library locale folders (appcompat alone contributes ~85) so
only the catalogued, `packaged = true` qualifiers ship. Phase 0 packages **only `en` and `en-rGB`**;
Phase 4 flips the remaining 20 Tier 1 locales to `packaged = true` one by one (or in regional batches).
This file is the **before** measurement so the per-locale APK-size delta is visible in the Phase 4 PRs
and a surprise regression (a library locale folder that slipped past the filter) is caught.

## Measurement method

```
AAPT2=~/Library/Android/sdk/build-tools/<version>/aapt2   # or $ANDROID_HOME/build-tools/<v>/aapt2
APK=app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk

stat -f "%z" "$APK"                         # total APK bytes (macOS; `stat -c %s` on Linux)
unzip -l "$APK" | grep resources.arsc       # resources.arsc stored size + compression
$AAPT2 dump configurations "$APK"           # locale configs actually packaged
python3 tools/i18n/check_pseudo_locales.py --apk "$APK" --mode release
```

The `resources.arsc` row reports the **stored** (post-compression) size; the first column is the
uncompressed size. The release APK is built with R8 optimization + resource shrinking, so the arsc is
the locale-filtered string table only.

## Phase 0 baseline (standard release, unsigned)

| Metric | Value |
|---|---|
| Total APK size | 51,693,164 bytes |
| `resources.arsc` stored (compressed) | 40,008 bytes, stored uncompressed |
| Packaged locale configs | `en`, `en-rGB` (verified via `aapt2 dump configurations` + `check_pseudo_locales.py --mode release`) |
| Pseudolocales in release | none (verified) |
| Build | `assembleStandardRelease`, arm64-v8a + armeabi-v7a ABI split, R8 optimization on |

## Phase 4 comparison template

When a locale (or batch) is flipped to `packaged = true`, re-measure and append a row here:

| Phase | Locales packaged | APK size | `resources.arsc` | Δ APK | Δ arsc |
|---|---|---|---|---|---|
| 0 | en, en-rGB | 51,693,164 | 40,008 | — | — |
| 4a | en, en-rGB + … | TBD | TBD | TBD | TBD |

A per-locale arsc delta in the tens-of-KB range is expected (a full string table for ~1,600 keys);
a delta in the hundreds of KB signals a library locale folder leaked through `localeFilters` and
should be investigated with `aapt2 dump configurations` before the PR merges.
