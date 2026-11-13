import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';

// A fake Authentik (OpenID Connect provider) and a fake DPTV-Server, then DPTV-Web pointed at both.
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'k1', alg: 'RS256', use: 'sig' };
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
let issuerUrl;
let lastNonce = null;
let subject = 'user-1';

function idToken(nonce) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64({ alg: 'RS256', kid: 'k1', typ: 'JWT' });
  const body = b64({ iss: issuerUrl, aud: 'dptv-web', sub: subject, preferred_username: 'damian', iat: now, exp: now + 300, nonce });
  const sig = crypto.sign('RSA-SHA256', Buffer.from(`${head}.${body}`), privateKey).toString('base64url');
  return `${head}.${body}.${sig}`;
}

const idp = http.createServer((req, res) => {
  const url = new URL(req.url, issuerUrl);
  res.setHeader('Content-Type', 'application/json');
  if (url.pathname === '/.well-known/openid-configuration') {
    return res.end(JSON.stringify({
      issuer: issuerUrl, authorization_endpoint: `${issuerUrl}/authorize`, token_endpoint: `${issuerUrl}/token`,
      jwks_uri: `${issuerUrl}/jwks`, response_types_supported: ['code'], subject_types_supported: ['public'],
      id_token_signing_alg_values_supported: ['RS256'],
    }));
  }
  if (url.pathname === '/jwks') return res.end(JSON.stringify({ keys: [jwk] }));
  if (url.pathname === '/authorize') {
    lastNonce = url.searchParams.get('nonce');
    res.writeHead(302, { Location: `${url.searchParams.get('redirect_uri')}?code=c1&state=${url.searchParams.get('state')}` });
    return res.end();
  }
  if (url.pathname === '/token') {
    return res.end(JSON.stringify({ access_token: 'a', token_type: 'Bearer', expires_in: 300, id_token: idToken(lastNonce) }));
  }
  res.writeHead(404); res.end();
});

const xc = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  res.setHeader('Content-Type', 'application/json');
  const ok = url.searchParams.get('username') === 'damian' && url.searchParams.get('password') === 'pw';
  if (url.pathname === '/player_api.php' && !url.searchParams.get('action')) {
    return res.end(JSON.stringify(ok ? { user_info: { auth: 1, status: 'Active', username: 'damian' } } : { user_info: { auth: 0 } }));
  }
  if (url.pathname === '/player_api.php') return res.end(ok ? '[{"category_id":1,"category_name":"Sport"}]' : '{"user_info":{"auth":0}}');
  res.writeHead(404); res.end();
});

let app;
let base;

before(async () => {
  await new Promise((r) => idp.listen(0, r));
  await new Promise((r) => xc.listen(0, r));
  issuerUrl = `http://127.0.0.1:${idp.address().port}`;
  // DPTV-Web's own port is not known before it listens; reserve one first.
  const probe = http.createServer();
  await new Promise((r) => probe.listen(0, r));
  const port = probe.address().port;
  await new Promise((r) => probe.close(r));
  Object.assign(process.env, {
    DATA_DIR: path.join(os.tmpdir(), `dptv-web-sso-${process.pid}`),
    DPTV_SERVER_URL: `http://127.0.0.1:${xc.address().port}`,
    OIDC_ISSUER: issuerUrl, OIDC_CLIENT_ID: 'dptv-web', OIDC_CLIENT_SECRET: 's3cret',
    PUBLIC_URL: `http://127.0.0.1:${port}`, OIDC_ONLY: 'true',
  });
  const { createApp } = await import('./index.js');
  app = createApp().listen(port);
  await new Promise((r) => app.once('listening', r));
  base = `http://127.0.0.1:${port}`;
});

after(() => { app.close(); idp.close(); xc.close(); });

/** Follows redirects by hand, carrying cookies, like a browser would; returns the final URL and cookies. */
async function browse(url, jar = new Map()) {
  for (let i = 0; i < 6; i++) {
    const res = await fetch(url, { redirect: 'manual', headers: { cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; ') } });
    for (const c of res.headers.getSetCookie()) {
      const [kv] = c.split(';');
      const [k, v] = kv.split('=');
      if (v) jar.set(k, v); else jar.delete(k);
    }
    const loc = res.headers.get('location');
    if (!loc) return { url, status: res.status, jar };
    url = new URL(loc, url).toString();
  }
  throw new Error('too many redirects');
}
const cookieHeader = (jar) => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');

test('the sign-in page offers single sign-on only', async () => {
  const cfg = await (await fetch(`${base}/api/config`)).json();
  assert.deepEqual(cfg.sso, { label: 'Sign in with Authentik', only: true });
  const pw = await fetch(`${base}/api/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'damian', password: 'pw' }) });
  assert.equal(pw.status, 403);
});

test('first sign-in asks once for the DPTV-Server login, the next goes straight in', async () => {
  const first = await browse(`${base}/api/auth/login`);
  assert.equal(new URL(first.url).search, '?sso_link=1');
  const pending = await (await fetch(`${base}/api/auth/pending`, { headers: { cookie: cookieHeader(first.jar) } })).json();
  assert.equal(pending.name, 'damian');

  const wrong = await fetch(`${base}/api/auth/link`, { method: 'POST', headers: { 'Content-Type': 'application/json', cookie: cookieHeader(first.jar) }, body: JSON.stringify({ username: 'damian', password: 'nope' }) });
  assert.equal(wrong.status, 401);
  const linked = await fetch(`${base}/api/auth/link`, { method: 'POST', headers: { 'Content-Type': 'application/json', cookie: cookieHeader(first.jar) }, body: JSON.stringify({ username: 'damian', password: 'pw' }) });
  assert.equal(linked.status, 200);
  const session = linked.headers.getSetCookie().find((c) => c.startsWith('dptv_session='));
  assert.ok(session);
  const me = await (await fetch(`${base}/api/me`, { headers: { cookie: session.split(';')[0] } })).json();
  assert.deepEqual(me.sso, { name: 'damian' });

  // Signing in again: no question, straight into the app with the linked login.
  const again = await browse(`${base}/api/auth/login`);
  assert.equal(new URL(again.url).pathname, '/');
  assert.equal(new URL(again.url).search, '');
  assert.ok(again.jar.get('dptv_session'));
  const cats = await fetch(`${base}/api/live/categories`, { headers: { cookie: `dptv_session=${again.jar.get('dptv_session')}` } });
  assert.deepEqual(await cats.json(), [{ id: '1', name: 'Sport' }]);
});

test('a callback without the flow cookie, or with a forged state, is refused', async () => {
  const noFlow = await fetch(`${base}/api/auth/callback?code=x&state=y`, { redirect: 'manual' });
  assert.match(noFlow.headers.get('location'), /sso_error=expired/);
  // Start a real flow, then come back with another state.
  const jar = new Map();
  const start = await fetch(`${base}/api/auth/login`, { redirect: 'manual' });
  for (const c of start.headers.getSetCookie()) { const [kv] = c.split(';'); const [k, v] = kv.split('='); jar.set(k, v); }
  const forged = await fetch(`${base}/api/auth/callback?code=c1&state=forged`, { redirect: 'manual', headers: { cookie: cookieHeader(jar) } });
  assert.match(forged.headers.get('location'), /sso_error=refused/);
});

test('a different person is asked for their own login', async () => {
  subject = 'user-2';
  const r = await browse(`${base}/api/auth/login`);
  assert.equal(new URL(r.url).search, '?sso_link=1');
  subject = 'user-1';
});
