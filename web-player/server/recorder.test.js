import assert from 'node:assert/strict';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';

process.env.DATA_DIR = path.join(os.tmpdir(), `dptv-web-rec-test-${process.pid}`);
const { createApp } = await import('./index.js');
const { normalizeRecorderUrl, mapRecording } = await import('./recorder.js');

const FILE = Buffer.alloc(1000, 0x47);
let xc;
let recorder;
let app;
let base;
let rows;
let seenRange = null;
let currentKey = 'dptv_key';
const signIns = [];

before(async () => {
  xc = http.createServer((req, res) => {
    res.setHeader('Content-Type', 'application/json');
    const user = new URL(req.url, 'http://x').searchParams.get('username');
    // The login 'tv' is on a DPTV-Server that advertises its recorder (DPTV-Server 1.0.11).
    const serverInfo = user === 'tv' ? { dptv_recorder_url: `http://127.0.0.1:${recorder.address().port}/` } : {};
    res.end(JSON.stringify({ user_info: { auth: 1, status: 'Active', username: user }, server_info: serverInfo }));
  });
  rows = [{ id: 1, channel_id: 7, channel_name: 'Sky Sport 1', program_title: 'Rugby', program_start: 1000, program_stop: 2000, status: 'completed', kind: 'live', filename: 'Rugby.ts' }];
  recorder = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    if (url.pathname === '/api/recording-file/tok') {
      seenRange = req.headers.range || null;
      if (seenRange === 'bytes=100-199') {
        res.writeHead(206, { 'Content-Type': 'video/mp2t', 'Content-Range': `bytes 100-199/${FILE.length}`, 'Content-Length': 100, 'Accept-Ranges': 'bytes' });
        return res.end(FILE.subarray(100, 200));
      }
      res.writeHead(200, { 'Content-Type': 'video/mp2t', 'Content-Length': FILE.length });
      return res.end(FILE);
    }
    res.setHeader('Content-Type', 'application/json');
    if (url.pathname === '/api/auth/dptv' && req.method === 'POST') {
      let raw = '';
      req.on('data', (d) => { raw += d; });
      req.on('end', () => {
        const b = JSON.parse(raw);
        signIns.push(b.device);
        if (b.username !== 'tv' || b.password !== 'pw') { res.writeHead(401); return res.end('{"error":"no"}'); }
        res.end(JSON.stringify({ apiKey: currentKey, username: 'tv' }));
      });
      return;
    }
    if (req.headers.authorization !== `Bearer ${currentKey}`) { res.writeHead(401); return res.end('{"error":"Not signed in"}'); }
    let body = '';
    req.on('data', (d) => { body += d; });
    req.on('end', () => {
      if (url.pathname === '/api/recordings' && req.method === 'GET') return res.end(JSON.stringify(rows));
      if (url.pathname === '/api/recordings' && req.method === 'POST') {
        const b = JSON.parse(body);
        const row = { id: 2, channel_id: b.channelId, channel_name: b.channelName, program_title: b.programTitle, program_start: b.programStart, program_stop: b.programStop, status: 'scheduled', kind: 'live' };
        rows.unshift(row);
        return res.end(JSON.stringify(row));
      }
      if (url.pathname === '/api/recordings/1/stream') return res.end(JSON.stringify({ url: '/api/recording-file/tok', filename: 'Rugby.ts', status: 'completed' }));
      const m = /^\/api\/recordings\/(\d+)$/.exec(url.pathname);
      if (m && req.method === 'DELETE') {
        rows = rows.filter((r) => String(r.id) !== m[1]);
        return res.end(JSON.stringify({ ok: true, finished: url.searchParams.get('status') }));
      }
      res.writeHead(404);
      res.end('{"error":"nope"}');
    });
  });
  await Promise.all([new Promise((r) => xc.listen(0, r)), new Promise((r) => recorder.listen(0, r))]);
  app = createApp().listen(0);
  await new Promise((r) => app.once('listening', r));
  base = `http://127.0.0.1:${app.address().port}`;
});

after(() => { xc.close(); recorder.close(); app.close(); });

test('recorder address and row mapping', () => {
  assert.equal(normalizeRecorderUrl('nas:3000/'), 'http://nas:3000');
  assert.equal(normalizeRecorderUrl(' https://rec.example.com/ '), 'https://rec.example.com');
  assert.equal(normalizeRecorderUrl(''), '');
  const r = mapRecording({ id: '3', channel_id: 9, program_title: 'X', program_start: '5', program_stop: '6', status: 'recording' });
  assert.equal(r.id, 3);
  assert.equal(r.channelId, '9');
  assert.equal(r.start, 5);
  assert.equal(r.kind, 'live');
});

test('dvr: set up, list, record, play with ranges, delete', async () => {
  const signIn = await fetch(`${base}/api/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ server: `127.0.0.1:${xc.address().port}`, username: 'damian', password: 'pw' }),
  });
  const cookie = signIn.headers.get('set-cookie').split(';')[0];
  const call = (p, init = {}) => fetch(`${base}${p}`, { ...init, headers: { cookie, 'Content-Type': 'application/json', ...(init.headers || {}) } });

  assert.equal((await (await call('/api/dvr/config')).json()).configured, false);
  assert.equal((await call('/api/dvr/recordings')).status, 409);

  const recUrl = `127.0.0.1:${recorder.address().port}`;
  const bad = await call('/api/dvr/config', { method: 'PUT', body: JSON.stringify({ url: recUrl, apiKey: 'wrong' }) });
  assert.equal(bad.status, 400);
  assert.match((await bad.json()).error, /API key/);

  const ok = await call('/api/dvr/config', { method: 'PUT', body: JSON.stringify({ url: recUrl, apiKey: 'dptv_key' }) });
  assert.equal(ok.status, 200);
  const cfg = await (await call('/api/dvr/config')).json();
  assert.equal(cfg.source, 'user');
  assert.ok(!JSON.stringify(cfg).includes('dptv_key'), 'the key never goes back to the browser');

  const list = await (await call('/api/dvr/recordings')).json();
  assert.equal(list[0].title, 'Rugby');
  assert.equal(list[0].channelId, '7');

  const made = await (await call('/api/dvr/recordings', { method: 'POST', body: JSON.stringify({ channelId: '7', channelName: 'Sky Sport 1', title: 'News', start: 3000, stop: 4000 }) })).json();
  assert.equal(made.status, 'scheduled');
  assert.equal(made.title, 'News');

  const play = await (await call('/api/dvr/recordings/1/play')).json();
  assert.deepEqual(play, { url: '/api/dvr/recordings/1/file', type: 'ts', growing: false });
  const part = await call('/api/dvr/recordings/1/file', { headers: { Range: 'bytes=100-199' } });
  assert.equal(part.status, 206);
  assert.equal(part.headers.get('content-range'), `bytes 100-199/${FILE.length}`);
  assert.equal((await part.arrayBuffer()).byteLength, 100);
  assert.equal(seenRange, 'bytes=100-199');

  assert.equal((await call('/api/dvr/recordings/1?status=completed', { method: 'DELETE' })).status, 200);
  assert.deepEqual((await (await call('/api/dvr/recordings')).json()).map((r) => r.id), [2]);

  const cleared = await (await call('/api/dvr/config', { method: 'DELETE' })).json();
  assert.equal(cleared.configured, false);
});

test('dvr without a session says 401', async () => {
  assert.equal((await fetch(`${base}/api/dvr/recordings`)).status, 401);
});

test('dvr: a DPTV-Server login signs itself in to the recorder its server advertises', async () => {
  const signIn = await fetch(`${base}/api/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ server: `127.0.0.1:${xc.address().port}`, username: 'tv', password: 'pw' }),
  });
  const cookie = signIn.headers.get('set-cookie').split(';')[0];
  const call = (p, init = {}) => fetch(`${base}${p}`, { ...init, headers: { cookie, 'Content-Type': 'application/json', ...(init.headers || {}) } });

  const cfg = await (await call('/api/dvr/config')).json();
  assert.equal(cfg.configured, true);
  assert.equal(cfg.source, 'dptv');
  assert.equal(cfg.url, `http://127.0.0.1:${recorder.address().port}`);
  assert.ok(!JSON.stringify(cfg).includes('dptv_key'), 'the key never goes back to the browser');
  assert.deepEqual(signIns, ['DPTV Web']);
  assert.ok(Array.isArray(await (await call('/api/dvr/recordings')).json()));
  assert.equal(signIns.length, 1, 'the key is kept, not asked for on every call');

  // The recorder withdrew the key (the login was disabled there, then enabled again): signed in afresh.
  currentKey = 'dptv_key2';
  assert.equal((await call('/api/dvr/recordings')).status, 200);
  assert.equal(signIns.length, 2);

  // Remove keeps it off for this login.
  const cleared = await (await call('/api/dvr/config', { method: 'DELETE' })).json();
  assert.equal(cleared.configured, false);
  assert.equal((await (await call('/api/dvr/config')).json()).configured, false);
  assert.equal(signIns.length, 2);
  currentKey = 'dptv_key';
});
