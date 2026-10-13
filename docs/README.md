# Sportio documentation

This is the map of everything a human or an agent needs to know about the **Sportio** Android TV
app and its core library. Start with the repo's [`AGENTS.md`](../AGENTS.md); this file is the index.

> **Two repositories, one product.** This folder documents the **app**. The **core** library has its
> own repo (`SportioTV_Core`) and its own `AGENTS.md` / `docs/`. Cross-cutting topics (playback,
> strings, the connection budget) are documented here and cross-linked there.

---

## The two focus areas

Sportio exists for these two things. If you are new, read them in this order:

1. **[Live TV](LIVE_TV.md)** — the player: channels, zapping, preview, guide, catch-up, timeshift,
   recording, multiview. This is the heart of the product.
2. **[Sports Hub](SPORTS_HUB.md)** — ESPN fixtures, scores and standings for 16 leagues, matched to
   the user's own channels.

## Reference

| Document | Covers |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Repo layout, module/package map, how app and core fit together, the README map |
| [PLAYBACK_ENGINES.md](PLAYBACK_ENGINES.md) | mpv and ExoPlayer: routing, the fallback ladder, watchdogs, per-channel pinning |
| [BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md) | Floors, flavors, signing, CI workflows, cutting a release |
| [I18N.md](I18N.md) | Where strings live and the checks that gate them |
| [I18N_RUNTIME.md](I18N_RUNTIME.md) | How the UI switches language at runtime (locale store, the four locals, pseudolocales) |
| [BRAND.md](BRAND.md) | Brand in brief; the full guide is [brand/BRAND_GUIDE_SPORTIO.md](brand/BRAND_GUIDE_SPORTIO.md) |
| [brand/](brand/) | Sportio brand guide, tokens, palette, logo assets |

## Everything else (not Sportio-specific)

| Document | Covers |
|---|---|
| [../extras/USER_GUIDE.md](../extras/USER_GUIDE.md) | End-user guide. Written for upstream OwnTV; the Movies/Series/Downloads parts do not apply here. |
| [../extras/OwnTV_Master_Product_Brief.md](../extras/OwnTV_Master_Product_Brief.md) | As-built product brief. Upstream-era; useful for background, superseded by these docs. |
| [../extras/worker/](../extras/worker/) | Optional self-hosted Cloudflare Workers for TMDB / OpenSubtitles metadata. |
| [../extras/player.html](../extras/player.html) | Interactive mock-up of the player UI. |

---

## For agents: how to use this

- **Orientation** → [`AGENTS.md`](../AGENTS.md).
- **A Live TV task** → [`LIVE_TV.md`](LIVE_TV.md), then the files it names.
- **A Sports Hub task** → [`SPORTS_HUB.md`](SPORTS_HUB.md), then the files it names.
- **A string change** → [`I18N.md`](I18N.md) and the core repo's `tools/i18n/README.md`.
- **A build/release task** → [`BUILD_AND_RELEASE.md`](BUILD_AND_RELEASE.md).

Each feature doc ends with a **Known gaps** section that lists the real limitations of that feature —
check it before assuming something is a bug.
