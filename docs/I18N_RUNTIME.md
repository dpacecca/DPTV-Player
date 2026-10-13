# i18n runtime (locale switching at runtime)

> How Sportio switches its UI language **live**, without a process restart, and why the pieces are
> shaped the way they are. This is the runtime side; for strings and the checks see
> [I18N.md](I18N.md).

Derived from the original architecture analysis. **Re-verify the framework facts against the installed
AGP/Compose versions before changing this layer** — the versions in the code comments below (compose-ui
1.11.1 / AGP 9.2.1) are the ones the mechanics were first confirmed against.

---

## 1. The `core/i18n/` package

```
core/i18n/
├── AppLocale.kt          resolve a stored tag → effective locale list; wrap() / applyGlobally()
├── LocaleStore.kt        THE single locale authority (SharedPreferences-backed)
├── LocalizedContent.kt   the @Composable wrapper providing the four locals
└── SupportedLocales.kt   generated from tools/i18n/locales.json + computed coverage
```

There is deliberately **no** `UiText.kt` and no `LocaleContextProvider.kt`.

| File | Responsibility |
|---|---|
| `AppLocale.kt` | `wrap(base, tag)` builds a locale-specific `Configuration` via `createConfigurationContext`. `applyGlobally(tag)` sets `Locale.setDefault()` **and** `LocaleList.setDefault()`. An empty tag reads the current device locale list from `Resources.getSystem().configuration.locales`. |
| `LocaleStore.kt` | The single locale authority. BCP-47 tag; `""` = follow system. Synchronous read for `attachBaseContext`, durable write, observable `StateFlow`. |
| `LocalizedContent.kt` | Provides the four composition locals (below). |
| `SupportedLocales.kt` | Generated: runtime tag, endonym, English name, script, RTL, informational coverage %. |

Companion (web) localisation lives with the companion feature (`core/companion/`), not here.

---

## 2. `LocaleStore` — one store, SharedPreferences, durable

**Do not** use a DataStore locale key, a DataStore+SharedPreferences mirror, dual writes, a mirror
repair collector, or mirror-race handling. There is **one** store.

The locale must be readable **synchronously** in `Application.attachBaseContext` and
`Activity.attachBaseContext` — DataStore is asynchronous and cannot be read from those hooks without
blocking startup. SharedPreferences is therefore the source of truth for this one bootstrap-critical
setting. It is **not** a licence to move other settings out of DataStore.

Every locale write goes through `LocaleStore`: the picker, reset-to-system, backup import, and any
settings-reset path. Backup export reads from it; there is no second path.

Prefer a suspend write using `commit()` off the main thread so the call returns only after persistence
succeeds. If `apply()` is used instead, accept the async durability and add a test that exercises
write-then-immediate-force-stop.

---

## 3. `""` means *actively follow the device locale*

The empty tag is a durable instruction to follow the TV's current locale list — not "leave the app
locale unchanged". Selecting "System default" must immediately apply the device locale, persist `""`,
and keep following later device-locale changes. `Resources.getSystem()` may be used **only** as the
source of device locale *metadata*; app strings must never resolve from it.

---

## 4. Backup interaction

The backup format still carries the UI language, but read it from `LocaleStore.currentTag` on export.
On import, validate the optional tag and apply it through `LocaleStore.set(tag)` **after** every
database/DataStore restore step and restore-marker cleanup, and before reporting success — so a
script-family recreation cannot interrupt an in-flight restore.

---

## 5. Verified mechanics (load-bearing — do not substitute intuition)

**The four locals, all four required.** `stringResource`, `pluralStringResource` and
`stringArrayResource` all read **`LocalResources.current`**. Providing `LocalConfiguration` alone
therefore changes nothing (it is read only to register an invalidation dependency).

```kotlin
CompositionLocalProvider(
    LocalConfiguration provides wrapped.resources.configuration,
    LocalContext provides wrapped,
    LocalResources provides wrapped.resources,
    LocalLayoutDirection provides layoutDirectionFor(locale),
) { content() }
```

- `LocalContext` must match `LocalResources` — `PainterResources` / `VectorResources` /
  `ColorResources` read both, and a mismatch breaks `ldrtl`-qualified drawables.
- `LocalLayoutDirection` **must be provided manually** — it normally comes from the View system's
  resolved direction, which an in-composition override never touches. Popups and dialogs do inherit it.
- `MainActivity.kt` already has a `CompositionLocalProvider` (for `LocalDensity`) to extend.

**Script change recreates once.** `LocalLocaleList` is `@RestrictTo` and cannot be overridden, so text
shaping and CJK fallback follow the Activity context. Same-script switches are instant; switching
script family triggers exactly one `Activity.recreate()`. Because `LocalContext` is
`staticCompositionLocalOf`, a language change already forces full subtree recomposition.

**Both `Application` and `Activity` must wrap.** `Configuration().apply { setLocales(...) }` +
`createConfigurationContext()`; `setLocales` also writes the `SCREENLAYOUT_LAYOUTDIR` bits, so `ldrtl`
resolves for free. Application-only leaves the Activity wrong — `createBaseContextForActivity` never
derives from the Application object.

**Application wrapping is retained for a platform reason.** `handleConfigurationChangedInner`
unconditionally calls `updateLocaleListFromAppContext(...)`, which **resets `Locale.getDefault()` to the
device locale on every system configuration change** unless the Application context carries your
locale. That affects `java.text`, `java.time`, `String.format` and every `Locale.getDefault()` reader.
So `OwnTVApp.onConfigurationChanged(newConfig)` must re-read `LocaleStore.readBlocking()` and call
`AppLocale.applyGlobally(tag)` after `super.onConfigurationChanged(newConfig)`.

Do not remove Application wrapping without an equally verified replacement covering configuration
changes, `Locale.getDefault()`, `LocaleList.getDefault()`, Java date/number formatting and process-level
locale resets.

---

## 6. Gradle: locale filters and debug pseudolocales

```kotlin
android {
    androidResources {
        // From the resourceQualifier field of tools/i18n/locales.json entries with packaged = true.
        // NOT languageTag, NOT weblateCode.
        localeFilters += packagedLocaleQualifiers      // MutableSet — use += / addAll, never =
    }
    buildTypes {
        debug { isPseudoLocalesEnabled = true }        // BuildType, NOT androidResources
    }
}
```

`localeFilters` strips **library** locale folders at merge time (appcompat alone contributes ~85) and
is what makes the catalogue's `packaged` flags meaningful — a catalogue-only entry can never add a
locale to a release just by existing in `locales.json`.

**`generateLocaleConfig` is deliberately not used**, and there is no hand-written
`android:localeConfig`: the app does not advertise a per-app-language surface.

**Pseudolocales must survive the filter.** `isPseudoLocalesEnabled = true` generates them, but
`localeFilters` can strip them again — and then the pseudolocale sweep silently does nothing. The
end state is:

```
Release:  packaged production locales only
Debug:    packaged production locales + en-rXA + ar-rXB
```

Note the two spellings, which are **not** interchangeable: runtime tags are `en-XA` / `ar-XB`, while
resource/filter qualifiers are `en-rXA` / `ar-rXB`. The debug-only qualifiers are re-added through the
per-variant API (`SetProperty<String>`, configured differently from the extension-level `MutableSet`).
**Re-verify the exact AGP syntax for the installed version before writing code.**

**Verification is mandatory and is not "the flag is set":** inspect the **built** debug APK and confirm
both pseudolocale configurations are present, and that the release APK contains neither. That is what
`tools/i18n/check_pseudo_locales.py` does.
