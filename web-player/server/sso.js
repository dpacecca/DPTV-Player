import express from 'express';
import oidc from 'openid-client';
import { config, oidcEnabled } from './config.js';
import { XcError, normalizeServer, signIn } from './xc.js';

const { Issuer, generators } = oidc;
const FLOW_COOKIE = 'dptv_sso_flow';
const PENDING_COOKIE = 'dptv_sso_pending';

/**
 * Single sign-on with an OpenID Connect provider (Authentik): the authorisation-code flow with PKCE. Once
 * the provider vouches for someone, they use, in order: the DPTV-Server login linked to them; the one of
 * the same username, when DPTV-Server shares DPTV_SERVER_LINK_KEY (2.9.15); SSO_XC_USERNAME's when set;
 * else the page asks them once.
 */
export function ssoRouter(sessions, links) {
  const router = express.Router();
  let clientPromise = null;

  function client() {
    if (!clientPromise) {
      clientPromise = Issuer.discover(config.oidc.issuer)
        .then((issuer) => new issuer.Client({
          client_id: config.oidc.clientId,
          client_secret: config.oidc.clientSecret || undefined,
          redirect_uris: [config.oidc.redirectUri],
          response_types: ['code'],
          token_endpoint_auth_method: config.oidc.clientSecret ? 'client_secret_basic' : 'none',
        }))
        .catch((err) => {
          clientPromise = null; // try the provider again next time
          throw err;
        });
    }
    return clientPromise;
  }

  const cookieOpts = (maxAgeMs) => ({ httpOnly: true, sameSite: 'lax', secure: config.cookieSecure, maxAge: maxAgeMs, path: '/' });
  const sharedLogin = () => (config.oidc.sharedUsername && config.serverUrl
    ? { server: config.serverUrl, username: config.oidc.sharedUsername, password: config.oidc.sharedPassword }
    : null);

  /**
   * (2.9.15) The DPTV-Server login with this person's username, checked; null when there is none, DPTV-Server
   * isn't sharing (no key) or can't be reached. Never logs the password.
   */
  async function matchedLogin(username) {
    const key = config.oidc.serverLinkKey;
    const server = normalizeServer(config.serverUrl || '');
    if (!key || !server || !username) return null;
    try {
      const res = await fetch(`${server}/api/integrations/xc-login?username=${encodeURIComponent(username)}`, {
        headers: { Authorization: `Bearer ${key}`, Accept: 'application/json' },
        signal: AbortSignal.timeout(10_000),
      });
      if (res.status === 401) console.warn('[sso] DPTV-Server did not accept DPTV_SERVER_LINK_KEY');
      if (!res.ok) return null;
      const found = await res.json();
      if (!found?.username || !found?.password) return null;
      const login = { server, username: String(found.username), password: String(found.password) };
      await signIn(login);
      return login;
    } catch (err) {
      console.warn(`[sso] no DPTV-Server login matched (${err instanceof XcError ? err.status : err.name})`);
      return null;
    }
  }

  /** Step 1: off to the provider, with a sealed note of what to check on the way back. */
  router.get('/login', async (req, res) => {
    if (!oidcEnabled()) return res.status(404).send('Single sign-on is not set up');
    try {
      const c = await client();
      const state = generators.state();
      const nonce = generators.nonce();
      const verifier = generators.codeVerifier();
      res.cookie(FLOW_COOKIE, sessions.seal({ state, nonce, verifier, exp: Date.now() + 10 * 60_000 }), cookieOpts(10 * 60_000));
      res.redirect(c.authorizationUrl({
        scope: config.oidc.scopes,
        state,
        nonce,
        code_challenge: generators.codeChallenge(verifier),
        code_challenge_method: 'S256',
      }));
    } catch (err) {
      console.warn(`[sso] the provider can't be reached: ${err.message}`);
      res.redirect('/?sso_error=provider');
    }
  });

  /** Step 2: the provider sends the person back; check it, then sign in with their DPTV-Server login. */
  router.get('/callback', async (req, res) => {
    const flow = req.cookies?.[FLOW_COOKIE] ? sessions.open(req.cookies[FLOW_COOKIE]) : null;
    res.clearCookie(FLOW_COOKIE, { path: '/' });
    if (!flow) return res.redirect('/?sso_error=expired');
    try {
      const c = await client();
      const tokens = await c.callback(config.oidc.redirectUri, c.callbackParams(req), {
        state: flow.state, nonce: flow.nonce, code_verifier: flow.verifier,
      });
      const claims = tokens.claims();
      const person = {
        sub: String(claims.sub),
        name: claims.preferred_username || claims.name || claims.email || String(claims.sub),
      };
      // A match is looked up at every sign-in, not kept, so a password changed on DPTV-Server follows.
      const login = links.get(person.sub)
        || await matchedLogin(claims.preferred_username ? String(claims.preferred_username) : '')
        || sharedLogin();
      if (login) {
        sessions.start(res, { ...login, sso: person });
        return res.redirect('/');
      }
      // First time: the page asks once for their DPTV-Server login.
      res.cookie(PENDING_COOKIE, sessions.seal({ ...person, exp: Date.now() + 15 * 60_000 }), cookieOpts(15 * 60_000));
      res.redirect('/?sso_link=1');
    } catch (err) {
      console.warn(`[sso] sign-in refused: ${err.message}`);
      res.redirect('/?sso_error=refused');
    }
  });

  /** Who is waiting to link a DPTV-Server login (after the provider signed them in). */
  router.get('/pending', (req, res) => {
    const p = req.cookies?.[PENDING_COOKIE] ? sessions.open(req.cookies[PENDING_COOKIE]) : null;
    res.json(p ? { name: p.name } : null);
  });

  /** Step 3, once: link the DPTV-Server login to this person, then sign in. */
  router.post('/link', express.json(), async (req, res) => {
    const p = req.cookies?.[PENDING_COOKIE] ? sessions.open(req.cookies[PENDING_COOKIE]) : null;
    if (!p) return res.status(401).json({ error: 'Sign in again' });
    const login = {
      server: normalizeServer(config.serverUrl || req.body?.server),
      username: String(req.body?.username || '').trim(),
      password: String(req.body?.password || ''),
    };
    if (!login.server || !login.username || !login.password) return res.status(400).json({ error: 'Enter your DPTV-Server login' });
    try {
      const account = await signIn(login);
      links.set(p.sub, login);
      res.clearCookie(PENDING_COOKIE, { path: '/' });
      sessions.start(res, { ...login, sso: { sub: p.sub, name: p.name } });
      res.json({ username: account.username });
    } catch (err) {
      res.status(err instanceof XcError ? err.status : 500).json({ error: err instanceof XcError ? err.message : 'Something went wrong' });
    }
  });

  /** Forget the DPTV-Server login linked to the signed-in person; the next sign-in asks again. */
  router.post('/unlink', sessions.required, (req, res) => {
    if (req.login.sso?.sub) links.remove(req.login.sso.sub);
    sessions.end(res);
    res.json({ ok: true });
  });

  return router;
}
