import { FormEvent, useEffect, useState } from 'react';
import { api } from './api';

type Config = { serverFixed: boolean; version: string; sso: { label: string; only: boolean } | null };

const SSO_ERRORS: Record<string, string> = {
  provider: 'The sign-in provider can\'t be reached.',
  expired: 'That sign-in took too long. Try again.',
  refused: 'Single sign-on was refused. Try again.',
};

/**
 * Sign in: through single sign-on (Authentik) when it is set up, and/or with an XC login on DPTV-Server.
 * After a first single sign-on, the same form links that person's DPTV-Server login, once.
 */
export function Login({ onSignedIn }: { onSignedIn: (user: string) => void }) {
  const params = new URLSearchParams(window.location.search);
  const [cfg, setCfg] = useState<Config | null>(null);
  const [linkFor, setLinkFor] = useState<string | null>(null);
  const [server, setServer] = useState(() => localStorageGet('dptv.server'));
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(() => SSO_ERRORS[params.get('sso_error') || ''] || null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.config().then(setCfg).catch(() => setCfg({ serverFixed: false, version: '', sso: null }));
    if (params.get('sso_link')) api.ssoPending().then((p) => setLinkFor(p?.name ?? null)).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = linkFor ? await api.ssoLink(server, username, password) : await api.login(server, username, password);
      if (!cfg?.serverFixed) localStorageSet('dptv.server', server);
      onSignedIn(linkFor || r.username);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!cfg) return <div className="splash">DPTV</div>;
  const showForm = Boolean(linkFor) || !cfg.sso?.only;

  return (
    <div className="login">
      <div className="login-card">
        <h1>DPTV</h1>
        {linkFor ? (
          <p className="muted">Welcome, {linkFor}. Enter your DPTV-Server login once; next time single sign-on is all it takes.</p>
        ) : (
          <p className="muted">{cfg.sso?.only ? 'Sign in to watch' : 'Sign in with your DPTV-Server login'}</p>
        )}
        {cfg.sso && !linkFor && (
          <a className="sso-button" href="/api/auth/login">{cfg.sso.label}</a>
        )}
        {cfg.sso && !linkFor && showForm && <div className="or"><span>or</span></div>}
        {showForm && (
          <form onSubmit={submit} className="login-form">
            {!cfg.serverFixed && (
              <label>Server address
                <input value={server} onChange={(e) => setServer(e.target.value)} placeholder="http://192.168.1.10:8000" autoComplete="url" required />
              </label>
            )}
            <label>User name
              <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus required />
            </label>
            <label>Password
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
            </label>
            <button disabled={busy}>{busy ? 'Signing in…' : linkFor ? 'Link and sign in' : 'Sign in'}</button>
          </form>
        )}
        {error && <div className="error">{error}</div>}
        {cfg.version && <div className="version muted">DPTV Web {cfg.version}</div>}
      </div>
    </div>
  );
}

function localStorageGet(key: string): string {
  try { return localStorage.getItem(key) || ''; } catch { return ''; }
}
function localStorageSet(key: string, value: string) {
  try { localStorage.setItem(key, value); } catch { /* private window */ }
}
