import { useEffect, useState } from 'react';
import { api, ApiError } from './api';
import { Login } from './Login';
import { Watch } from './Watch';

export function App() {
  const [user, setUser] = useState<string | null | undefined>(undefined);
  const [sso, setSso] = useState(false);
  useEffect(() => {
    api.me()
      .then((m) => { setUser(m.sso?.name || m.username); setSso(Boolean(m.sso)); })
      .catch((e) => setUser(e instanceof ApiError && e.status === 401 ? null : null));
  }, []);
  if (user === undefined) return <div className="splash">DPTV</div>;
  if (user === null) return <Login onSignedIn={(u) => { window.history.replaceState(null, '', '/'); setUser(u); }} />;
  return <Watch user={user} sso={sso} onSignedOut={() => { setUser(null); setSso(false); }} />;
}
