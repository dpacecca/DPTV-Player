import fs from 'node:fs';
import path from 'node:path';
import { Readable } from 'node:stream';
import express from 'express';
import { config } from './config.js';
import { playerApi, XcError } from './xc.js';

/**
 * Recording through a DPTV-Recorder server, the same way DPTV-Player on Android does it
 * (core RemoteRecorder.kt): schedule, list, cancel/delete and play back, authenticated with an API key
 * made in the recorder's Settings → Account → API keys.
 *
 * The recorder is set for everyone with RECORDER_URL + RECORDER_API_KEY, or per login in the page's
 * Settings (kept in the data folder, sealed with the session key). The key never reaches the browser:
 * every call, and every byte of a recording, goes through this container.
 *
 * (2.9.14) With neither, a login whose DPTV-Server advertises a recorder (server_info.dptv_recorder_url)
 * signs in there with that same login (DPTV-Recorder 2.7.0, POST /api/auth/dptv) and keeps the key the
 * recorder issues. One set by hand (here or for everyone) always wins; Remove turns this off for the login.
 */

/** "nas:3000/" → "http://nas:3000"; empty for blank input. */
export function normalizeRecorderUrl(raw) {
  const trimmed = String(raw || '').trim();
  if (!trimmed) return '';
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
  try {
    const url = new URL(withScheme);
    return url.toString().replace(/\/+$/, '');
  } catch {
    return '';
  }
}

/** One recording as the page needs it. Times are epoch ms. */
export function mapRecording(r) {
  return {
    id: Number(r.id),
    channelId: String(r.channel_id ?? ''),
    channelName: r.channel_name || '',
    title: r.program_title || '',
    start: Number(r.program_start) || 0,
    stop: Number(r.program_stop) || 0,
    recStart: Number(r.rec_start) || 0,
    recEnd: Number(r.rec_end) || 0,
    status: String(r.status || 'failed'),
    kind: r.kind || 'live',
    error: r.error || null,
    filename: r.filename || null,
  };
}

function createStore(dataDir, sessions) {
  const file = path.join(dataDir, 'recorder-settings.json');
  let entries = {};
  try {
    entries = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    entries = {};
  }
  function save() {
    try {
      fs.mkdirSync(dataDir, { recursive: true });
      fs.writeFileSync(`${file}.tmp`, JSON.stringify(entries), { mode: 0o600 });
      fs.renameSync(`${file}.tmp`, file);
    } catch (err) {
      console.warn(`[recorder] could not save the recorder settings (${err.code})`);
    }
  }
  return {
    get(key) {
      const sealed = entries[key];
      if (!sealed) return null;
      const v = sessions.open(sealed);
      return v ? { url: v.url || '', apiKey: v.apiKey || '', auto: Boolean(v.auto), off: Boolean(v.off) } : null;
    },
    set(key, value) {
      entries[key] = sessions.seal({ ...value, exp: Date.now() + 100 * 365 * 24 * 60 * 60 * 1000 });
      save();
    },
    remove(key) {
      delete entries[key];
      save();
    },
  };
}

const loginKey = (l) => `${l.server}|${l.username}`;
const AUTO_RETRY_MS = 60 * 60_000;

/** One JSON call to the recorder. Errors come back as XcError with the recorder's own words. */
export async function recorderCall(rec, pathAndQuery, { method = 'GET', body, timeoutMs = 15_000 } = {}) {
  let res;
  try {
    res = await fetch(rec.url + pathAndQuery, {
      method,
      headers: {
        Authorization: `Bearer ${rec.apiKey}`,
        Accept: 'application/json',
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (err) {
    throw new XcError(502, `DPTV-Recorder can't be reached (${err.name})`);
  }
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = null; }
  if (res.status === 401) throw Object.assign(new XcError(400, 'DPTV-Recorder did not accept the API key'), { unauthorized: true });
  if (!res.ok) throw new XcError(res.status >= 500 ? 502 : 400, json?.error || `DPTV-Recorder answered ${res.status}`);
  if (json === null && text) throw new XcError(502, 'That address is not a DPTV-Recorder');
  return json;
}

export function recorderRouter(sessions) {
  const router = express.Router();
  const store = createStore(config.dataDir, sessions);
  /** Short-lived file links per recording, so a seek does not ask the recorder for a new one every time. */
  const fileLinks = new Map();

  const envRecorder = () => {
    const url = normalizeRecorderUrl(config.recorder.url);
    return url && config.recorder.apiKey ? { url, apiKey: config.recorder.apiKey } : null;
  };
  /** Logins whose sign-in with DPTV-Server failed lately: tried again after an hour, not on every call. */
  const autoFailedAt = new Map();

  /** (2.9.14) Signs this login in to the recorder its DPTV-Server advertises; null when there is none. */
  async function signInWithServer(login) {
    const key = loginKey(login);
    if (Date.now() - (autoFailedAt.get(key) || 0) < AUTO_RETRY_MS) return null;
    try {
      const info = await playerApi(login, {}, { timeoutMs: 10_000 });
      const url = normalizeRecorderUrl(info?.server_info?.dptv_recorder_url);
      if (!url) throw new Error('no recorder advertised');
      const res = await fetch(`${url}/api/auth/dptv`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ username: login.username, password: login.password, device: 'DPTV Web' }),
        signal: AbortSignal.timeout(15_000),
      });
      const out = await res.json().catch(() => null);
      const apiKey = String(out?.apiKey || '');
      if (!res.ok || !apiKey.startsWith('dptv_')) throw new Error(`recorder answered ${res.status}`);
      store.set(key, { url, apiKey, auto: true });
      autoFailedAt.delete(key);
      console.log('[dvr] signed in to DPTV-Recorder with a DPTV-Server login');
      return { url, apiKey, auto: true };
    } catch (err) {
      autoFailedAt.set(key, Date.now());
      console.warn(`[dvr] DPTV-Server sign-in to the recorder skipped (${err instanceof XcError ? err.status : err.message})`);
      return null;
    }
  }

  /** Where this login records: its own recorder, then the container's, then one signed in with DPTV-Server. */
  async function recorderOf(login) {
    const own = store.get(loginKey(login));
    if (own && !own.auto && !own.off) return { ...own, source: 'user' };
    const env = envRecorder();
    if (env) return { ...env, source: 'server' };
    if (own?.off) return null;
    if (own?.auto) return { ...own, source: 'dptv' };
    const signed = await signInWithServer(login);
    return signed ? { ...signed, source: 'dptv' } : null;
  }

  /** One call for this login; a signed-in key the recorder stopped taking is dropped and signed in again once. */
  async function callFor(req, pathAndQuery, opts) {
    const rec = await need(req);
    try {
      return await recorderCall(rec, pathAndQuery, opts);
    } catch (err) {
      if (!(err?.unauthorized && rec.auto)) throw err;
      store.remove(loginKey(req.login));
      return recorderCall(await need(req), pathAndQuery, opts);
    }
  }

  const wrap = (fn) => (req, res) => fn(req, res).catch((err) => {
    const status = err instanceof XcError ? err.status : 500;
    if (!(err instanceof XcError)) console.error('[dvr]', err);
    if (!res.headersSent) res.status(status).json({ error: err instanceof XcError ? err.message : 'Something went wrong' });
    else res.end();
  });
  const need = async (req) => {
    const rec = await recorderOf(req.login);
    if (!rec) throw new XcError(409, 'Recording is not set up: add your DPTV-Recorder in Settings');
    return rec;
  };

  router.use(sessions.required);

  router.get('/config', wrap(async (req, res) => {
    const rec = await recorderOf(req.login);
    res.json({
      configured: Boolean(rec),
      /** Where it comes from: this login's own settings, the container's, or signed in with DPTV-Server. */
      source: rec?.source || null,
      url: rec?.url || '',
    });
  }));

  router.put('/config', express.json(), wrap(async (req, res) => {
    const url = normalizeRecorderUrl(req.body?.url);
    const apiKey = String(req.body?.apiKey || '').trim();
    if (!url || !apiKey) throw new XcError(400, 'Enter the recorder\'s address and an API key');
    const list = await recorderCall({ url, apiKey }, '/api/recordings');
    if (!Array.isArray(list)) throw new XcError(502, 'That address is not a DPTV-Recorder');
    store.set(loginKey(req.login), { url, apiKey });
    res.json({ configured: true, source: 'user', url, recordings: list.length });
  }));

  router.delete('/config', (req, res) => {
    const own = store.get(loginKey(req.login));
    // Removing the one signed in with DPTV-Server keeps it off for this login; removing one's own falls back.
    if (own?.auto) store.set(loginKey(req.login), { off: true });
    else store.remove(loginKey(req.login));
    const env = envRecorder();
    res.json({ configured: Boolean(env), source: env ? 'server' : null, url: env?.url || '' });
  });

  router.get('/recordings', wrap(async (req, res) => {
    const list = await callFor(req, '/api/recordings');
    res.json((Array.isArray(list) ? list : []).map(mapRecording));
  }));

  router.post('/recordings', express.json(), wrap(async (req, res) => {
    const { channelId, channelName, title, start, stop } = req.body || {};
    if (!channelId || !title || !Number(start) || !Number(stop)) throw new XcError(400, 'Pick a programme to record');
    const row = await callFor(req, '/api/recordings', {
      method: 'POST',
      body: { channelId: String(channelId), channelName: String(channelName || ''), programTitle: String(title), programStart: Number(start), programStop: Number(stop) },
    });
    res.json(mapRecording(row));
  }));

  /** Cancel a scheduled or running recording (?finished=0), or delete a finished one and its file. */
  router.delete('/recordings/:id', wrap(async (req, res) => {
    const id = encodeURIComponent(req.params.id);
    const finished = ['completed', 'failed', 'cancelled'].includes(String(req.query.status));
    await callFor(req, `/api/recordings/${id}${finished ? '?status=completed' : ''}`, { method: 'DELETE' });
    fileLinks.delete(`${loginKey(req.login)}|${req.params.id}`);
    res.json({ ok: true });
  }));

  /** What the page needs to play a recording: how (ts / mkv) and where (the relay below). */
  router.get('/recordings/:id/play', wrap(async (req, res) => {
    const link = await fileLink(req, true);
    const ext = path.extname(link.filename || '').toLowerCase();
    res.json({
      url: `/api/dvr/recordings/${encodeURIComponent(req.params.id)}/file`,
      type: ext === '.mkv' ? 'mkv' : ext === '.mp4' ? 'mp4' : 'ts',
      growing: link.status === 'recording',
    });
  }));

  /** The recording's file, relayed with Range support so the player can seek. */
  router.get('/recordings/:id/file', wrap(async (req, res) => {
    let link = await fileLink(req, false);
    let upstream = await openFile(link, req);
    if (upstream.status === 404) {
      // The link expired on the recorder (e.g. it restarted): ask for a fresh one once.
      link = await fileLink(req, true);
      upstream = await openFile(link, req);
    }
    if (!upstream.ok && upstream.status !== 206) {
      res.status(upstream.status === 416 ? 416 : 502).json({ error: `DPTV-Recorder answered ${upstream.status}` });
      return;
    }
    res.status(upstream.status);
    for (const h of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'last-modified', 'etag']) {
      const v = upstream.headers.get(h);
      if (v) res.setHeader(h, v);
    }
    if (!upstream.headers.get('accept-ranges')) res.setHeader('Accept-Ranges', 'bytes');
    res.setHeader('Cache-Control', 'no-store');
    if (!upstream.body) return res.end();
    const body = Readable.fromWeb(upstream.body);
    body.on('error', () => res.end());
    req.on('close', () => body.destroy());
    body.pipe(res);
  }));

  async function fileLink(req, fresh) {
    const key = `${loginKey(req.login)}|${req.params.id}`;
    const hit = fileLinks.get(key);
    if (!fresh && hit && Date.now() - hit.at < 60 * 60_000) return hit;
    const rec = await need(req);
    const info = await callFor(req, `/api/recordings/${encodeURIComponent(req.params.id)}/stream`);
    if (!info?.url || !String(info.url).startsWith('/')) throw new XcError(404, 'This recording has no file yet');
    const link = { at: Date.now(), url: rec.url + info.url, filename: info.filename || '', status: info.status || '' };
    fileLinks.set(key, link);
    return link;
  }

  async function openFile(link, req) {
    const controller = new AbortController();
    req.on('close', () => controller.abort());
    try {
      return await fetch(link.url, {
        headers: req.headers.range ? { Range: req.headers.range } : {},
        signal: controller.signal,
      });
    } catch (err) {
      throw new XcError(502, `DPTV-Recorder can't be reached (${err.name})`);
    }
  }

  return router;
}
