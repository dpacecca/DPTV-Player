import express from 'express';
import { Readable } from 'node:stream';
import { config, oidcEnabled } from './config.js';
import { XcError, decodeShortEpg, liveStreamUrl, normalizeServer, playerApi, signIn, xmltvUrl } from './xc.js';
import { inWindow, parseXmltv } from './xmltv.js';

/** Small time-boxed cache, one entry per login and kind. */
function cache(ttlMs) {
  const entries = new Map();
  return async (key, load) => {
    const hit = entries.get(key);
    if (hit && Date.now() - hit.at < ttlMs) return hit.value;
    if (hit?.pending) return hit.pending;
    const pending = load();
    entries.set(key, { ...hit, pending });
    try {
      const value = await pending;
      entries.set(key, { at: Date.now(), value });
      return value;
    } catch (err) {
      if (hit && hit.value !== undefined) { entries.set(key, hit); return hit.value; }
      entries.delete(key);
      throw err;
    }
  };
}

const loginKey = (l) => `${l.server}|${l.username}`;

export function apiRouter(sessions) {
  const router = express.Router();
  const channelsCache = cache(config.channelsCacheMs);
  const categoriesCache = cache(config.channelsCacheMs);
  const guideCache = cache(config.guideCacheMs);

  const wrap = (fn) => (req, res) => fn(req, res).catch((err) => {
    const status = err instanceof XcError ? err.status : 500;
    if (!(err instanceof XcError)) console.error('[api]', err);
    res.status(status).json({ error: err instanceof XcError ? err.message : 'Something went wrong' });
  });

  router.get('/config', (req, res) => res.json({
    serverFixed: Boolean(config.serverUrl),
    version: config.version,
    sso: oidcEnabled() ? { label: config.oidc.buttonLabel, only: config.oidc.only } : null,
  }));

  router.post('/login', express.json(), wrap(async (req, res) => {
    if (oidcEnabled() && config.oidc.only) throw new XcError(403, 'Sign in through single sign-on');
    const server = normalizeServer(config.serverUrl || req.body?.server);
    const username = String(req.body?.username || '').trim();
    const password = String(req.body?.password || '');
    if (!server || !username || !password) throw new XcError(400, 'Enter the server, user name and password');
    const login = { server, username, password };
    const account = await signIn(login);
    sessions.start(res, login);
    res.json({ username: account.username, expires: account.expires });
  }));

  router.post('/logout', (req, res) => { sessions.end(res); res.json({ ok: true }); });

  router.use(sessions.required);

  router.get('/me', (req, res) => res.json({
    username: req.login.username,
    server: config.serverUrl ? null : req.login.server,
    sso: req.login.sso ? { name: req.login.sso.name } : null,
  }));

  router.get('/live/categories', wrap(async (req, res) => {
    const cats = await categoriesCache(loginKey(req.login), () => playerApi(req.login, { action: 'get_live_categories' }));
    res.json((Array.isArray(cats) ? cats : []).map((c) => ({ id: String(c.category_id), name: c.category_name })));
  }));

  router.get('/live/channels', wrap(async (req, res) => {
    const list = await channelsCache(loginKey(req.login), () => playerApi(req.login, { action: 'get_live_streams' }));
    res.json((Array.isArray(list) ? list : []).map((s) => ({
      id: String(s.stream_id),
      num: s.num != null ? Number(s.num) : null,
      name: s.name,
      logo: s.stream_icon || null,
      categoryId: s.category_id != null ? String(s.category_id) : null,
      epgId: s.epg_channel_id || null,
      catchup: Boolean(Number(s.tv_archive || 0)),
    })));
  }));

  /** Now and next for one channel (the player's info line). */
  router.get('/live/:id/epg', wrap(async (req, res) => {
    const data = await playerApi(req.login, { action: 'get_short_epg', stream_id: req.params.id, limit: 4 });
    res.json((data?.epg_listings || []).map(decodeShortEpg));
  }));

  /** The whole XMLTV, read once per GUIDE_CACHE_MINUTES; windows are cut from it. */
  const loadGuide = (login) => guideCache(loginKey(login), async () => {
    let upstream;
    try {
      upstream = await fetch(xmltvUrl(login), { headers: { 'User-Agent': config.userAgent }, signal: AbortSignal.timeout(120_000) });
    } catch (err) {
      throw new XcError(502, `The guide can't be fetched (${err.name})`);
    }
    if (!upstream.ok || !upstream.body) throw new XcError(502, `The guide answered ${upstream.status}`);
    return parseXmltv(Readable.fromWeb(upstream.body), { fromMs: Date.now() - 3 * 60 * 60_000 });
  });

  function cut(guide, from, to, ids) {
    const out = {};
    if (ids) {
      for (const id of ids) {
        const shown = inWindow(guide.get(id), from, to);
        if (shown.length) out[id] = shown;
      }
      return out;
    }
    for (const [id, list] of guide) {
      const shown = inWindow(list, from, to);
      if (shown.length) out[id] = shown;
    }
    return out;
  }

  const windowOf = (q) => {
    const now = Date.now();
    const from = Number(q.from) || now - 60 * 60_000;
    const to = Number(q.to) || now + 6 * 60 * 60_000;
    return { from, to };
  };

  /** The guide for [from, to) (epoch ms), keyed by the channels' EPG ids; ?ids=a,b limits it. */
  router.get('/guide', wrap(async (req, res) => {
    const { from, to } = windowOf(req.query);
    const ids = req.query.ids ? new Set(String(req.query.ids).split(',')) : null;
    res.json({ from, to, channels: cut(await loadGuide(req.login), from, to, ids) });
  }));

  /**
   * (2.9.16) The EPG ids with a programme in [from, to) whose title contains ?q (any case), so the page's
   * search finds programmes on every channel, not only those it has loaded. At most 2000 ids.
   */
  router.get('/guide/search', wrap(async (req, res) => {
    const { from, to } = windowOf(req.query);
    const q = String(req.query.q || '').trim().toLowerCase();
    if (q.length < 2) return res.json({ ids: [] });
    const ids = [];
    for (const [id, list] of await loadGuide(req.login)) {
      if (inWindow(list, from, to).some((p) => p.title.toLowerCase().includes(q))) ids.push(id);
      if (ids.length >= 2000) break;
    }
    res.json({ ids });
  }));

  /**
   * The same for a batch of EPG ids in the body ({ from, to, ids }), so the page loads the guide for the
   * rows it shows instead of every channel at once.
   */
  router.post('/guide', express.json({ limit: '1mb' }), wrap(async (req, res) => {
    const { from, to } = windowOf(req.body || {});
    const ids = Array.isArray(req.body?.ids) ? new Set(req.body.ids.map(String).slice(0, 2000)) : null;
    res.json({ from, to, channels: cut(await loadGuide(req.login), from, to, ids) });
  }));

  /**
   * A live channel as one continuous MPEG-TS stream, relayed from DPTV-Server (which hands the request on
   * to the provider). The login never reaches the browser; closing the player closes the upstream stream.
   */
  router.get('/live/:id/stream', async (req, res) => {
    const controller = new AbortController();
    req.on('close', () => controller.abort());
    let upstream;
    try {
      upstream = await fetch(liveStreamUrl(req.login, req.params.id, 'ts'), {
        headers: { 'User-Agent': config.userAgent },
        redirect: 'follow',
        signal: controller.signal,
      });
    } catch (err) {
      if (!controller.signal.aborted) res.status(502).json({ error: `The stream can't be opened (${err.name})` });
      return;
    }
    if (!upstream.ok || !upstream.body) {
      // The provider's refusal (e.g. 511 when the line's connections are all in use) goes to the player as is.
      res.status(upstream.status === 200 ? 502 : upstream.status).json({ error: `The provider answered ${upstream.status}` });
      return;
    }
    res.status(200);
    res.setHeader('Content-Type', 'video/mp2t');
    res.setHeader('Cache-Control', 'no-store');
    res.flushHeaders();
    const body = Readable.fromWeb(upstream.body);
    body.on('error', () => res.end());
    body.pipe(res);
    req.on('close', () => body.destroy());
  });

  return router;
}
