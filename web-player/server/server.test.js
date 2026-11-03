import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';

process.env.DATA_DIR = path.join(os.tmpdir(), `dptv-web-test-${process.pid}`);
const { parseXmltv, parseXmltvTime, inWindow } = await import('./xmltv.js');
const { createSessions } = await import('./session.js');
const { normalizeServer, decodeShortEpg } = await import('./xc.js');
const { createApp } = await import('./index.js');

test('xmltv times with and without an offset', () => {
  assert.equal(parseXmltvTime('20261102183000 +1300'), Date.UTC(2026, 10, 2, 5, 30));
  assert.equal(parseXmltvTime('20261102183000'), Date.UTC(2026, 10, 2, 18, 30));
  assert.equal(parseXmltvTime('nonsense'), null);
});

test('xmltv programmes by channel, sorted, old ones dropped, window cut', async () => {
  const xml = `<?xml version="1.0"?><tv>
    <channel id="a"><display-name>A</display-name></channel>
    <programme channel="a" start="20261102120000 +0000" stop="20261102130000 +0000"><title>Noon &amp; one</title><desc>D</desc></programme>
    <programme channel="a" start="20261102100000 +0000" stop="20261102110000 +0000"><title>Ten</title></programme>
    <programme channel="a" start="20261101100000 +0000" stop="20261101110000 +0000"><title>Yesterday</title></programme>
    <programme channel="b" start="20261102120000 +0000" stop="20261102123000 +0000"><title><![CDATA[News]]></title></programme>
  </tv>`;
  const guide = await parseXmltv([Buffer.from(xml)], { fromMs: Date.UTC(2026, 10, 2, 0, 0) });
  assert.deepEqual(guide.get('a').map((p) => p.title), ['Ten', 'Noon & one']);
  assert.equal(guide.get('b')[0].title, 'News');
  assert.deepEqual(inWindow(guide.get('a'), Date.UTC(2026, 10, 2, 11, 30), Date.UTC(2026, 10, 2, 14)).map((p) => p.title), ['Noon & one']);
});

test('sessions open what they sealed, refuse tampering and expiry', () => {
  const s = createSessions(crypto.randomBytes(32));
  const token = s.seal({ server: 'http://x', username: 'u', password: 'p', exp: Date.now() + 1000 });
  assert.equal(s.open(token).username, 'u');
  assert.equal(s.open(token.slice(0, -2) + 'AA'), null);
  assert.equal(s.open(s.seal({ username: 'u', exp: Date.now() - 1 })), null);
});

test('server address and short epg', () => {
  assert.equal(normalizeServer('192.168.1.10:8000/'), 'http://192.168.1.10:8000');
  assert.equal(normalizeServer('https://xc.example.com'), 'https://xc.example.com');
  const e = decodeShortEpg({ title: Buffer.from('Héllo').toString('base64'), description: '', start_timestamp: '10', stop_timestamp: '20' });
  assert.deepEqual(e, { title: 'Héllo', description: '', start: 10_000, stop: 20_000 });
});

// --- against a fake DPTV-Server ------------------------------------------------------------------------

let fake;
let app;
let base;
let seenUserAgent = null;

before(async () => {
  fake = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://fake');
    seenUserAgent = req.headers['user-agent'];
    const ok = url.searchParams.get('username') === 'damian' && url.searchParams.get('password') === 'pw';
    if (url.pathname === '/player_api.php') {
      res.setHeader('Content-Type', 'application/json');
      if (!ok) return res.end(JSON.stringify({ user_info: { auth: 0 } }));
      const action = url.searchParams.get('action');
      if (!action) return res.end(JSON.stringify({ user_info: { auth: 1, status: 'Active', username: 'damian', exp_date: null } }));
      if (action === 'get_live_categories') return res.end(JSON.stringify([{ category_id: 1, category_name: 'Sport' }]));
      if (action === 'get_live_streams') return res.end(JSON.stringify([{ stream_id: 7, num: 1, name: 'Sky Sport 1', category_id: 1, epg_channel_id: 'sky1' }]));
      return res.end('[]');
    }
    if (url.pathname === '/xmltv.php') {
      res.setHeader('Content-Type', 'application/xml');
      const t = (ms) => new Date(ms).toISOString().replace(/[-:T]/g, '').slice(0, 14) + ' +0000';
      const now = Date.now();
      return res.end(`<tv><programme channel="sky1" start="${t(now - 600_000)}" stop="${t(now + 600_000)}"><title>Rugby</title></programme></tv>`);
    }
    // DPTV-Server redirects a stream to the provider; the provider sends TS.
    if (url.pathname === '/live/damian/pw/7.ts') { res.writeHead(302, { Location: '/provider/7.ts' }); return res.end(); }
    if (url.pathname === '/provider/7.ts') { res.writeHead(200, { 'Content-Type': 'video/mp2t' }); return res.end(Buffer.alloc(376, 0x47)); }
    if (url.pathname === '/live/damian/pw/8.ts') { res.writeHead(511); return res.end(); }
    res.writeHead(404); res.end();
  });
  await new Promise((r) => fake.listen(0, r));
  process.env.DPTV_SERVER_URL = '';
  app = createApp().listen(0);
  await new Promise((r) => app.once('listening', r));
  base = `http://127.0.0.1:${app.address().port}`;
});

after(() => { fake.close(); app.close(); });

async function login(password = 'pw') {
  return fetch(`${base}/api/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ server: `127.0.0.1:${fake.address().port}`, username: 'damian', password }),
  });
}

test('a wrong password is refused and sets no cookie', async () => {
  const r = await login('nope');
  assert.equal(r.status, 401);
  assert.equal(r.headers.get('set-cookie'), null);
});

test('signed in: channels, guide and the stream relayed through the redirect', async () => {
  const r = await login();
  assert.equal(r.status, 200);
  const cookie = r.headers.get('set-cookie').split(';')[0];
  assert.ok(!cookie.includes('pw'), 'the password is not readable in the cookie');
  const get = (p) => fetch(`${base}${p}`, { headers: { cookie } });

  assert.deepEqual(await (await get('/api/live/categories')).json(), [{ id: '1', name: 'Sport' }]);
  const channels = await (await get('/api/live/channels')).json();
  assert.equal(channels[0].name, 'Sky Sport 1');
  assert.equal(channels[0].epgId, 'sky1');

  const guide = await (await get('/api/guide')).json();
  assert.equal(guide.channels.sky1[0].title, 'Rugby');
  const batch = await (await fetch(`${base}/api/guide`, {
    method: 'POST', headers: { cookie, 'Content-Type': 'application/json' },
    body: JSON.stringify({ from: Date.now() - 3_600_000, to: Date.now() + 3_600_000, ids: ['sky1', 'none'] }),
  })).json();
  assert.deepEqual(Object.keys(batch.channels), ['sky1']);

  const stream = await get('/api/live/7/stream');
  assert.equal(stream.status, 200);
  assert.equal(stream.headers.get('content-type'), 'video/mp2t');
  assert.equal((await stream.arrayBuffer()).byteLength, 376);
  assert.match(seenUserAgent, /VLC/);

  const refused = await get('/api/live/8/stream');
  assert.equal(refused.status, 511);
});

test('without a session the api says 401', async () => {
  assert.equal((await fetch(`${base}/api/live/channels`)).status, 401);
});
