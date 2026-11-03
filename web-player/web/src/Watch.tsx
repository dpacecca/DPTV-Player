import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, ApiError, Category, Channel, Programme, Recording, RecorderConfig } from './api';
import { Dvr } from './Dvr';
import { LiveTv, localGet, localSet, RecordActions } from './LiveTv';
import { Settings } from './Settings';

type View = 'live' | 'dvr';

/** The signed-in page: Live TV (guide + player) and the DVR, like DPTV-Player's rail on Android. */
export function Watch({ user, sso, onSignedOut }: { user: string; sso: boolean; onSignedOut: () => void }) {
  const [view, setView] = useState<View>(() => (window.location.hash === '#dvr' ? 'dvr' : 'live'));
  const [categories, setCategories] = useState<Category[]>([]);
  const [channels, setChannels] = useState<Channel[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [version, setVersion] = useState('');
  const [recorder, setRecorder] = useState<RecorderConfig | null>(null);
  const [recordings, setRecordings] = useState<Recording[] | null>(null);
  const [recError, setRecError] = useState<string | null>(null);
  const favKey = `dptv.favourites.${user}`;
  const [favourites, setFavourites] = useState<Set<string>>(() => new Set(JSON.parse(localGet(favKey) || '[]') as string[]));

  const signedOutOn401 = useCallback((e: unknown) => {
    if (e instanceof ApiError && e.status === 401) { onSignedOut(); return true; }
    return false;
  }, [onSignedOut]);

  useEffect(() => {
    Promise.all([api.categories(), api.channels()])
      .then(([c, ch]) => { setCategories(c); setChannels(ch); })
      .catch((e) => { if (!signedOutOn401(e)) setError(e.message); })
      .finally(() => setLoading(false));
    api.dvrConfig().then(setRecorder).catch(() => setRecorder({ configured: false, source: null, url: '' }));
    api.config().then((c) => setVersion(c.version)).catch(() => undefined);
  }, [signedOutOn401]);

  useEffect(() => { window.history.replaceState(null, '', view === 'dvr' ? '#dvr' : window.location.pathname); }, [view]);

  const refreshRecordings = useCallback(() => {
    if (!recorder?.configured) { setRecordings(null); return; }
    api.recordings()
      .then((r) => { setRecordings(r); setRecError(null); })
      .catch((e) => { if (!signedOutOn401(e)) setRecError(e.message); });
  }, [recorder, signedOutOn401]);

  useEffect(() => {
    refreshRecordings();
    if (!recorder?.configured) return;
    const t = setInterval(refreshRecordings, 30_000);
    return () => clearInterval(t);
  }, [recorder, refreshRecordings]);

  const byProgramme = useMemo(() => {
    const m = new Map<string, Recording>();
    // Oldest first, so the newest attempt at a programme wins.
    for (const r of [...(recordings || [])].reverse()) if (r.status !== 'cancelled') m.set(`${r.channelId}|${r.start}|${r.stop}`, r);
    return m;
  }, [recordings]);

  const rec: RecordActions = {
    configured: Boolean(recorder?.configured),
    recordingOf: useCallback((c: Channel, p: Programme) => byProgramme.get(`${c.id}|${p.start}|${p.stop}`), [byProgramme]),
    record: async (c, p) => {
      const r = await api.record(c, p);
      setRecordings((list) => [r, ...(list || []).filter((x) => x.id !== r.id)]);
      setTimeout(refreshRecordings, 2000);
    },
    remove: async (r) => {
      await api.removeRecording(r);
      setRecordings((list) => (list || []).filter((x) => x.id !== r.id));
      setTimeout(refreshRecordings, 1000);
    },
    openSettings: () => setSettingsOpen(true),
  };

  function toggleFavourite(id: string) {
    setFavourites((f) => {
      const next = new Set(f);
      if (next.has(id)) next.delete(id); else next.add(id);
      localSet(favKey, JSON.stringify([...next]));
      return next;
    });
  }

  async function signOut() {
    await api.logout().catch(() => undefined);
    onSignedOut();
  }

  /** Single sign-on: forget the linked DPTV-Server login; the next sign-in asks for one again. */
  async function changeLogin() {
    if (!window.confirm('Forget the DPTV-Server login linked to you? You\'ll be asked for one at the next sign-in.')) return;
    await api.ssoUnlink().catch(() => undefined);
    onSignedOut();
  }

  const active = (recordings || []).filter((r) => r.status === 'recording').length;
  const scheduled = (recordings || []).filter((r) => r.status === 'scheduled').length;

  return (
    <div className="watch">
      <header>
        <Brand />
        <nav>
          <button className={view === 'live' ? 'tab on' : 'tab'} onClick={() => setView('live')}>Live TV</button>
          <button className={view === 'dvr' ? 'tab on' : 'tab'} onClick={() => setView('dvr')}>
            DVR
            {active > 0 && <span className="badge red" title={`${active} recording now`}>{active}</span>}
            {active === 0 && scheduled > 0 && <span className="badge" title={`${scheduled} scheduled`}>{scheduled}</span>}
          </button>
        </nav>
        {view === 'live' && (
          <input className="search" type="search" placeholder="Search channels" value={query} onChange={(e) => setQuery(e.target.value)} />
        )}
        <div className="header-end">
          <span className="muted user">{user}</span>
          <button className="icon-btn" onClick={() => setSettingsOpen(true)} title="Settings" aria-label="Settings">
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M19.4 13a7.6 7.6 0 0 0 0-2l2-1.6-2-3.4-2.4 1a7.4 7.4 0 0 0-1.7-1l-.4-2.6h-4l-.4 2.6a7.4 7.4 0 0 0-1.7 1l-2.4-1-2 3.4 2 1.6a7.6 7.6 0 0 0 0 2l-2 1.6 2 3.4 2.4-1a7.4 7.4 0 0 0 1.7 1l.4 2.6h4l.4-2.6a7.4 7.4 0 0 0 1.7-1l2.4 1 2-3.4-2-1.6ZM12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7Z" /></svg>
          </button>
        </div>
      </header>
      {error && <div className="banner error">{error}</div>}
      {loading && <div className="loading-bar" />}
      {view === 'live' ? (
        <LiveTv categories={categories} channels={channels} query={query} favourites={favourites} toggleFavourite={toggleFavourite} rec={rec} />
      ) : (
        <Dvr
          recordings={recordings}
          error={recError}
          configured={rec.configured}
          channels={channels}
          onRemove={rec.remove}
          onRefresh={refreshRecordings}
          openSettings={() => setSettingsOpen(true)}
        />
      )}
      {settingsOpen && (
        <Settings
          recorder={recorder}
          onRecorder={setRecorder}
          user={user}
          sso={sso}
          version={version}
          onClose={() => setSettingsOpen(false)}
          onSignOut={signOut}
          onChangeLogin={changeLogin}
        />
      )}
    </div>
  );
}

/** DPTV-Player's wordmark: the green play glyph and DPTV PLAYER in Barlow Condensed. */
export function Brand() {
  return (
    <span className="brand">
      <svg viewBox="0 0 32 32" width="26" height="26" aria-hidden="true">
        <rect x="1" y="1" width="30" height="30" rx="8" fill="#141c17" stroke="#2a3a30" />
        <path d="M12 9.5v13l11-6.5z" fill="var(--accent)" />
      </svg>
      <span className="brand-word">DPTV <span>PLAYER</span></span>
    </span>
  );
}
