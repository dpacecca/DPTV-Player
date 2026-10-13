# Brand

> Sportio's brand is green, direct and TV-first. The **binding** reference is the
> [Brand Guide (German)](brand/BRAND_GUIDE_SPORTIO.md); this page is the short version for agents.

## The essentials

| | |
|---|---|
| **Name** | `Sportio` — capital S, the rest lower-case, never `SPORTIO` or `Sport IO` |
| **Tagline** | Am Ball bleiben. Live, ohne Umwege. |
| **Signature green (dark)** | `#4FEE94` |
| **Signature green (light)** | `#0E6B41` |
| **Focus ring (dark)** | `#81EEB0` |
| **Wordmark** | "Sport" in the signature green, "io" neutral |
| **Type** | Plus Jakarta Sans (variable), the same font the app renders |
| **Tone** | Direct, calm, no exclamation marks, no jargon in the UI |

- **Red is reserved** — it means *live now* (or a recording/error), never decoration.
- **Colour is never the only signal** — always pair it with a glyph or word.
- **The upstream `owntv` wordmark and `owntv_*` assets must not be used.** Sportio uses its own
  `sportio_*` drawables, mark, wordmark and banner.

## Where the assets are

| | |
|---|---|
| Brand guide (binding) | [`docs/brand/BRAND_GUIDE_SPORTIO.md`](brand/BRAND_GUIDE_SPORTIO.md) |
| Machine-readable tokens | [`docs/brand/sportio.tokens.json`](brand/sportio.tokens.json) |
| Kotlin palette drop-in | [`docs/brand/SportioPalette.kt`](brand/SportioPalette.kt) |
| SVG / PNG / Android assets | [`docs/brand/assets/`](brand/assets/) |
| Generator + audits | [`tools/brand/`](../tools/brand/) |
| README logo | `docs/brand/assets/png/sportio-logo{,-light}.png` |

The palette also exists inside core as `SportioPalette` (`core/.../core/theme/`), which is what the app
actually renders. Change the guide and the tokens **together** with it.
