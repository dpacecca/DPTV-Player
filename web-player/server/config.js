import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

/** Settings from the environment (Docker / Unraid template). */
export const config = {
  port: Number(process.env.PORT || 3000),
  /** DPTV-Server's address, e.g. http://192.168.1.10:8000. When set, the login form only asks for the XC login. */
  serverUrl: (process.env.DPTV_SERVER_URL || '').trim().replace(/\/+$/, ''),
  /** The User-Agent sent to DPTV-Server and the provider, as DPTV-Player sends by default. */
  userAgent: process.env.USER_AGENT || 'VLC/3.0.20 LibVLC/3.0.20',
  dataDir: process.env.DATA_DIR || '/data',
  /** Mark the session cookie Secure (set when the page is only reached over HTTPS, e.g. a Cloudflare Tunnel). */
  cookieSecure: process.env.COOKIE_SECURE === 'true',
  /** How long the guide and channel list are kept before asking DPTV-Server again. */
  guideCacheMs: Number(process.env.GUIDE_CACHE_MINUTES || 30) * 60_000,
  channelsCacheMs: Number(process.env.CHANNELS_CACHE_MINUTES || 5) * 60_000,
  /** The release this image was built from (set by the release workflow). */
  version: process.env.DPTV_WEB_VERSION || 'dev',
  /**
   * Single sign-on through an OpenID Connect provider such as Authentik. On when OIDC_ISSUER and
   * OIDC_CLIENT_ID are set.
   */
  oidc: {
    issuer: (process.env.OIDC_ISSUER || '').trim(),
    clientId: (process.env.OIDC_CLIENT_ID || '').trim(),
    clientSecret: process.env.OIDC_CLIENT_SECRET || '',
    /** Where the provider sends people back; default: PUBLIC_URL + /api/auth/callback. */
    redirectUri: (process.env.OIDC_REDIRECT_URI || '').trim()
      || ((process.env.PUBLIC_URL || '').trim().replace(/\/+$/, '') ? `${(process.env.PUBLIC_URL || '').trim().replace(/\/+$/, '')}/api/auth/callback` : ''),
    scopes: process.env.OIDC_SCOPES || 'openid profile email',
    buttonLabel: process.env.OIDC_BUTTON_LABEL || 'Sign in with Authentik',
    /** Hide the password form: everyone signs in through the provider. */
    only: process.env.OIDC_ONLY === 'true',
    /** Optional: one DPTV-Server login every signed-in person uses until they link their own. */
    sharedUsername: (process.env.SSO_XC_USERNAME || '').trim(),
    sharedPassword: process.env.SSO_XC_PASSWORD || '',
  },
};

export const oidcEnabled = () => Boolean(config.oidc.issuer && config.oidc.clientId);

/**
 * The key the session cookie is sealed with: SESSION_SECRET, else one made on first start and kept in the
 * data folder, so a restart does not sign everyone out.
 */
export function sessionKey() {
  const fromEnv = process.env.SESSION_SECRET;
  if (fromEnv) return crypto.createHash('sha256').update(fromEnv).digest();
  const file = path.join(config.dataDir, 'session.key');
  try {
    return Buffer.from(fs.readFileSync(file, 'utf8').trim(), 'hex');
  } catch {
    const key = crypto.randomBytes(32);
    try {
      fs.mkdirSync(config.dataDir, { recursive: true });
      fs.writeFileSync(file, key.toString('hex'), { mode: 0o600 });
    } catch (err) {
      console.warn(`[config] could not keep the session key in ${config.dataDir} (${err.code}); sessions end on restart`);
    }
    return key;
  }
}
