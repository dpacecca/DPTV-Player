import { config } from './config.js';

/** Errors the browser can be told about plainly. */
export class XcError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** "192.168.1.10:8000/" and "http://host:8000" both become "http://host:8000". */
export function normalizeServer(raw) {
  const trimmed = String(raw || '').trim().replace(/\/+$/, '');
  if (!trimmed) return '';
  return /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
}

function apiUrl(login, params = {}) {
  const url = new URL(`${login.server}/player_api.php`);
  url.searchParams.set('username', login.username);
  url.searchParams.set('password', login.password);
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null) url.searchParams.set(k, String(v));
  return url;
}

/** One player_api.php call as JSON. Never logs the URL (it carries the password). */
export async function playerApi(login, params = {}, { timeoutMs = 20_000 } = {}) {
  let res;
  try {
    res = await fetch(apiUrl(login, params), {
      headers: { 'User-Agent': config.userAgent, Accept: 'application/json' },
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (err) {
    throw new XcError(502, `DPTV-Server can't be reached (${err.name})`);
  }
  if (res.status === 401 || res.status === 403) throw new XcError(401, 'Wrong user name or password');
  if (!res.ok) throw new XcError(502, `DPTV-Server answered ${res.status}`);
  try {
    return await res.json();
  } catch {
    throw new XcError(502, 'DPTV-Server sent something that is not JSON');
  }
}

/** Checks a login; the account's details when it works. */
export async function signIn(login) {
  const info = await playerApi(login);
  const user = info?.user_info;
  if (!user || String(user.auth) === '0') throw new XcError(401, 'Wrong user name or password');
  if (user.status && !['Active', 'active'].includes(user.status)) throw new XcError(403, `This login is ${user.status}`);
  return { username: user.username || login.username, expires: user.exp_date ? Number(user.exp_date) * 1000 : null };
}

export function liveStreamUrl(login, streamId, ext = 'ts') {
  return `${login.server}/live/${encodeURIComponent(login.username)}/${encodeURIComponent(login.password)}/${encodeURIComponent(streamId)}.${ext}`;
}

export function xmltvUrl(login) {
  const url = new URL(`${login.server}/xmltv.php`);
  url.searchParams.set('username', login.username);
  url.searchParams.set('password', login.password);
  return url;
}

/** get_short_epg titles and descriptions come base64-encoded. */
export function decodeShortEpg(listing) {
  const dec = (s) => {
    if (!s) return '';
    try {
      return Buffer.from(String(s), 'base64').toString('utf8');
    } catch {
      return String(s);
    }
  };
  return {
    title: dec(listing.title),
    description: dec(listing.description),
    start: Number(listing.start_timestamp) * 1000,
    stop: Number(listing.stop_timestamp) * 1000,
  };
}
