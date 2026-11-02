import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, Category, Channel, Guide, Programme } from './api';
import { GuideGrid } from './GuideGrid';
import { Player } from './Player';
import { clock, floorHalfHour, HALF_HOUR, MIN } from './time';

type View = 'live' | 'guide';
const ALL = '__all__';

/** Live TV (categories, channels, the player and what's on) and the TV guide, one page. */
export function Watch({ user, sso, onSignedOut }: { user: string; sso: boolean; onSignedOut: () => void }) {
  const [view, setView] = useState<View>(() => (sessionGet('dptv.view') as View) || 'guide');
  const [categories, setCategories] = useState<Category[]>([]);
  const [channels, setChannels] = useState<Channel[]>([]);
  const [category, setCategory] = useState<string>(() => sessionGet('dptv.category') || ALL);
  const [playing, setPlaying] = useState<Channel | null>(null);
  const [query, setQuery] = useState('');
  const [guide, setGuide] = useState<Guide | null>(null);
  const [now, setNow] = useState(Date.now());
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([api.categories(), api.channels()])
      .then(([c, ch]) => { setCategories(c); setChannels(ch); })
      .catch((e) => (e.status === 401 ? onSignedOut() : setError(e.message)));
  }, [onSignedOut]);

  const windowStart = floorHalfHour(now) - HALF_HOUR;
  const windowEnd = windowStart + 8 * 60 * MIN;
  const loadGuide = useCallback(() => {
    api.guide(windowStart, windowEnd).then(setGuide).catch(() => setGuide({ from: windowStart, to: windowEnd, channels: {} }));
  }, [windowStart, windowEnd]);
  useEffect(() => { loadGuide(); }, [loadGuide]);
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(t);
  }, []);
  useEffect(() => { sessionSet('dptv.view', view); }, [view]);
  useEffect(() => { sessionSet('dptv.category', category); }, [category]);

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return channels.filter((c) => (category === ALL || c.categoryId === category) && (!q || c.name.toLowerCase().includes(q)));
  }, [channels, category, query]);

  const programmesOf = (c: Channel | null): Programme[] => (c?.epgId && guide?.channels[c.epgId]) || [];
  const onNow = (c: Channel | null) => programmesOf(c).find((p) => p.start <= now && p.stop > now);
  const nowNext = programmesOf(playing).filter((p) => p.stop > now).slice(0, 2);

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

  return (
    <div className="watch">
      <header>
        <span className="brand">DPTV</span>
        <nav>
          <button className={view === 'guide' ? 'tab on' : 'tab'} onClick={() => setView('guide')}>TV Guide</button>
          <button className={view === 'live' ? 'tab on' : 'tab'} onClick={() => setView('live')}>Live TV</button>
        </nav>
        <input className="search" placeholder="Search channels" value={query} onChange={(e) => setQuery(e.target.value)} />
        <span className="muted user">{user}</span>
        {sso && <button className="link muted" onClick={changeLogin}>Change login</button>}
        <button className="link" onClick={signOut}>Sign out</button>
      </header>
      {error && <div className="banner error">{error}</div>}
      <div className="body">
        <aside className="categories">
          <button className={category === ALL ? 'cat on' : 'cat'} onClick={() => setCategory(ALL)}>All channels <span className="muted">{channels.length}</span></button>
          {categories.map((c) => (
            <button key={c.id} className={category === c.id ? 'cat on' : 'cat'} onClick={() => setCategory(c.id)}>{c.name}</button>
          ))}
        </aside>
        <main>
          <section className="top">
            <Player channelId={playing?.id ?? null} title={playing?.name} />
            <div className="info">
              {playing ? (
                <>
                  <div className="info-channel">
                    {playing.logo && <img src={playing.logo} alt="" />}
                    <span>{playing.num != null && <b>{playing.num} </b>}{playing.name}</span>
                  </div>
                  {nowNext.length === 0 && <p className="muted">No guide for this channel</p>}
                  {nowNext.map((p, i) => (
                    <div key={p.start} className="info-prog">
                      <div className="muted">{i === 0 ? 'Now' : 'Next'} · {clock(p.start)}–{clock(p.stop)}</div>
                      <div className="info-title">{p.title}</div>
                      {i === 0 && p.desc && <p className="desc">{p.desc}</p>}
                    </div>
                  ))}
                </>
              ) : <p className="muted">Pick a channel from the {view === 'guide' ? 'guide' : 'list'}.</p>}
            </div>
          </section>
          {view === 'guide' ? (
            <GuideGrid
              channels={shown}
              guide={guide}
              from={windowStart}
              to={windowEnd}
              now={now}
              playingId={playing?.id ?? null}
              onPlay={setPlaying}
            />
          ) : (
            <ul className="channel-list">
              {shown.map((c) => {
                const p = onNow(c);
                return (
                  <li key={c.id}>
                    <button className={playing?.id === c.id ? 'channel on' : 'channel'} onClick={() => setPlaying(c)}>
                      <span className="num">{c.num ?? ''}</span>
                      {c.logo ? <img src={c.logo} alt="" loading="lazy" /> : <span className="logo-blank" />}
                      <span className="name">{c.name}</span>
                      <span className="now muted">{p ? `${p.title} · until ${clock(p.stop)}` : ''}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </main>
      </div>
    </div>
  );
}

function sessionGet(key: string): string | null {
  try { return sessionStorage.getItem(key); } catch { return null; }
}
function sessionSet(key: string, value: string) {
  try { sessionStorage.setItem(key, value); } catch { /* private window */ }
}
