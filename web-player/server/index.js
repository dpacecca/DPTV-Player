import cookieParser from 'cookie-parser';
import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { config, sessionKey } from './config.js';
import { createLinkStore } from './links.js';
import { apiRouter } from './routes.js';
import { createSessions } from './session.js';
import { ssoRouter } from './sso.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const webDir = path.join(here, '..', 'web', 'dist');

export function createApp() {
  const app = express();
  app.disable('x-powered-by');
  app.use(cookieParser());
  const sessions = createSessions(sessionKey(), { secure: config.cookieSecure });
  app.get('/healthz', (req, res) => res.json({ ok: true }));
  app.use('/api/auth', ssoRouter(sessions, createLinkStore(config.dataDir, sessions)));
  app.use('/api', apiRouter(sessions));
  app.use(express.static(webDir, { index: false, maxAge: '1h' }));
  // The single-page app answers every other path.
  app.get('*', (req, res) => res.sendFile(path.join(webDir, 'index.html'), (err) => { if (err && !res.headersSent) res.status(404).send('The web page is not built (npm run build).'); }));
  return app;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  createApp().listen(config.port, () => {
    console.log(`[dptv-web] ${config.version} listening on :${config.port}${config.serverUrl ? ` (DPTV-Server ${config.serverUrl})` : ''}${config.oidc.issuer ? ' with single sign-on' : ''}`);
  });
}
