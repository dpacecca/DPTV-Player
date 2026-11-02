# DPTV Web

Part of [DPTV-Player](../README.md): released with it, as a Docker image.

Watch **Live TV** and browse the **TV guide** in a browser, signed in to **DPTV-Server** with an XC login,
the same login the TVs use. Runs as one small Docker container (Unraid template included).

- **Live TV**: categories, a channel list with what's on now, search.
- **TV Guide**: a timeline of programmes with the now-line; click a channel or programme to watch.
- **The player**: the container relays each channel as one continuous MPEG-TS stream and the page plays it
  with [mpegts.js](https://github.com/xqq/mpegts.js). No transcoding, so very little CPU.

Your login never reaches the browser: it is sealed (AES-GCM) into an HttpOnly cookie that only the
container can read, and every request to DPTV-Server and the provider is made by the container.

## Single sign-on with Authentik

1. In Authentik: **Applications → Providers → Create → OAuth2/OpenID Provider**.
   - Client type: **Confidential**. Copy the **Client ID** and **Client Secret**.
   - Redirect URI: `https://<your DPTV Web address>/api/auth/callback` (strict).
   - Signing key: any certificate (RS256). Scopes: the default `openid`, `profile`, `email`.
2. **Applications → Create**, slug `dptv-web`, with that provider. Bind a policy or group to it to choose
   who may sign in.
3. On the container, set:
   - `PUBLIC_URL` - the address people open, e.g. `https://dptvweb.pacecca.com`
   - `OIDC_ISSUER` - the provider's **OpenID Configuration Issuer**, e.g.
     `https://auth.pacecca.com/application/o/dptv-web/`
   - `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`
   - `OIDC_ONLY=true` to hide the password form, and `COOKIE_SECURE=true` behind HTTPS.

The sign-in page then shows **Sign in with Authentik**. The first time someone signs in, DPTV Web asks
once for their DPTV-Server XC login, checks it, and keeps it linked to them (sealed with the container's
key in `/data/sso-links.json`); after that, Authentik alone signs them in. **Change login** in the header
forgets the link. To skip the question for everyone, set `SSO_XC_USERNAME` / `SSO_XC_PASSWORD` to one XC
login they all use.

## Limits of this first version

- **Browsers**: Chrome, Edge and Firefox. **Not Safari / iPhone** (no MPEG-TS playback there).
- **Codecs**: H.264 channels. **HEVC channels (most 4K) don't play** in browsers.
- **Connections**: a channel playing in the browser uses one connection of the playlist's line, like a
  TV. If the line is busy, the provider refuses it and the player says so.
- Live TV and the guide only: no Movies / TV Shows, recordings or shared profile yet.

## Install on Unraid

1. Copy `unraid-template.xml` to the flash drive as
   `/boot/config/plugins/dockerMan/templates-user/my-dptv-web.xml` (the repository is private, so
   Unraid can't fetch it by URL), then Docker → **Add Container** → Template: **dptv-web**.
2. Set **DPTV-Server URL** to your server (e.g. `http://192.168.1.10:8000`), pick a port, and apply.
3. Open `http://<unraid-ip>:3040/` and sign in with an XC login from DPTV-Server.

Behind a Cloudflare Tunnel (HTTPS), set **Secure cookie** to `true`.

### docker compose

See `docker-compose.yml`.

## Settings

| Variable | Default | |
|---|---|---|
| `DPTV_SERVER_URL` | (empty) | Your DPTV-Server. Empty: the sign-in page asks for it. |
| `PORT` | `3000` | Port inside the container. |
| `COOKIE_SECURE` | `false` | `true` when served only over HTTPS. |
| `SESSION_SECRET` | (made on first start) | Seals the sign-in cookie. Kept in `/data/session.key` when not set. |
| `USER_AGENT` | `VLC/3.0.20 LibVLC/3.0.20` | Sent to DPTV-Server and the provider. |
| `PUBLIC_URL` | | The address people open; single sign-on returns to `PUBLIC_URL/api/auth/callback`. |
| `OIDC_ISSUER` | | Single sign-on provider (Authentik's issuer URL). Empty: no single sign-on. |
| `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` | | The provider's client. |
| `OIDC_ONLY` | `false` | `true`: no password form. |
| `OIDC_REDIRECT_URI` | `PUBLIC_URL/api/auth/callback` | Only if it must differ. |
| `OIDC_SCOPES` | `openid profile email` | |
| `OIDC_BUTTON_LABEL` | `Sign in with Authentik` | |
| `SSO_XC_USERNAME` / `SSO_XC_PASSWORD` | | One XC login every SSO user uses until they link their own. |
| `GUIDE_CACHE_MINUTES` | `30` | How long the guide is kept before fetching it again. |
| `CHANNELS_CACHE_MINUTES` | `5` | How long the channel list is kept. |

## Publishing (with each DPTV-Player release)

DPTV Web lives in the DPTV-Player repository (`web-player/`). Every DPTV-Player release (the Android CI
workflow's release button, or a `v*` tag) also tests and builds this image for `linux/amd64` +
`linux/arm64` and pushes `<DOCKERHUB_USERNAME>/dptv-web:latest` and `:<version>` (e.g. `:2.9.5`) to
Docker Hub. It needs two repository secrets on DPTV-Player (Settings → Secrets and variables → Actions):
`DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` (a Docker Hub access token with Read & Write) - the same two
DPTV-Recorder uses. Without them the release still happens and the image step is skipped with a
warning. Pull requests that touch `web-player/` run its tests (Web player workflow).

## Development

```sh
npm install && npm --prefix web install
npm --prefix web run build
DATA_DIR=./data DPTV_SERVER_URL=http://192.168.1.10:8000 npm start   # http://localhost:3000
npm test                                                             # server tests
```

`npm --prefix web run dev` serves the page with hot reload and proxies `/api` to `npm start`.
