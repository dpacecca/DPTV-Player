import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, Category, Channel, Programme, Recording } from './api';
import { GuideGrid, Selection, TvGlyph } from './GuideGrid';
import { Player } from './Player';
import { clock, dateTime, DAY, dayLabel, duration, startOfDay } from './time';

export const ALL = '__all__';
export const FAVOURITES = '__fav__';
const DAYS = 7;
const BATCH = 250;

export type RecordActions = {
  configured: boolean;
  recordingOf: (c: Channel, p: Programme) => Recording | undefined;
  record: (c: Channel, p: Programme) => Promise<void>;
  remove: (r: Recording) => Promise<void>;
  openSettings: () => void;
};

/**
 * Live TV, laid out like DPTV-Player's Live TV on Android (v2): the video and what's on at the top, the
 * day chips, then the categories and the guide. Click a channel or programme to preview the channel (and see the programme, with Record);
 * double-click it, or the video, for full screen.
 */
export function LiveTv({ categories, channels, query, onQuery, favourites, toggleFavourite, rec }: {
  categories: Category[];
  channels: Channel[];
  query: string;
  onQuery: (q: string) => void;
  favourites: Set<string>;
  toggleFavourite: (id: string) => void;
  rec: RecordActions;
}) {
  const [now, setNow] = useState(Date.now());
  const today = startOfDay(now);
  const [dayOffset, setDayOffset] = useState(0);
  const from = today + dayOffset * DAY;
  const to = from + DAY;
  const [category, setCategory] = useState<string>(() => sessionGet('dptv.category') || ALL);
  const [panelOpen, setPanelOpen] = useState(() => window.innerWidth >= 1000);
  const [playing, setPlaying] = useState<Channel | null>(null);
  const [selection, setSelection] = useState<Selection>(null);
  const [, setGuideVersion] = useState(0);
  const playerBox = useRef<HTMLDivElement>(null);

  // The guide, per window (day) and EPG id; loaded in batches for the rows in view.
  const guide = useRef(new Map<number, Map<string, Programme[]>>());
  const pending = useRef(new Set<string>());

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(t);
  }, []);
  useEffect(() => { sessionSet('dptv.category', category); }, [category]);

  // Start on the last channel watched in this browser.
  useEffect(() => {
    if (playing || channels.length === 0) return;
    const last = localGet('dptv.lastChannel');
    const c = last ? channels.find((x) => x.id === last) : null;
    if (c) setPlaying(c);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channels]);

  // (2.9.16) The search finds channels by name and, from the guide on the server, by programme title on the day shown.
  const [titleHits, setTitleHits] = useState<Set<string>>(new Set());
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) { setTitleHits(new Set()); return; }
    let stale = false;
    const t = window.setTimeout(() => {
      api.guideSearch(q, from, to)
        .then((r) => { if (!stale) setTitleHits(new Set(r.ids)); })
        .catch(() => { if (!stale) setTitleHits(new Set()); });
    }, 300);
    return () => { stale = true; window.clearTimeout(t); };
  }, [query, from, to]);

  // Back to today, the timeline on now.
  const [jumpKey, setJumpKey] = useState(0);
  const jumpToNow = () => { setDayOffset(0); setJumpKey((k) => k + 1); };

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (q) return channels.filter((c) => c.name.toLowerCase().includes(q) || (c.epgId && titleHits.has(c.epgId)));
    if (category === FAVOURITES) return channels.filter((c) => favourites.has(c.id));
    if (category === ALL) return channels;
    return channels.filter((c) => c.categoryId === category);
  }, [channels, category, query, favourites, titleHits]);

  const counts = useMemo(() => {
    const m = new Map<string, number>();
    for (const c of channels) if (c.categoryId) m.set(c.categoryId, (m.get(c.categoryId) || 0) + 1);
    return m;
  }, [channels]);

  const programmesFor = useCallback((c: Channel, windowFrom: number): Programme[] | undefined => {
    if (!c.epgId) return [];
    return guide.current.get(windowFrom)?.get(c.epgId);
  }, []);

  const load = useCallback((windowFrom: number, ids: string[]) => {
    let byId = guide.current.get(windowFrom);
    if (!byId) guide.current.set(windowFrom, (byId = new Map()));
    const want = [...new Set(ids)].filter((id) => !byId!.has(id) && !pending.current.has(`${windowFrom}|${id}`));
    for (let i = 0; i < want.length; i += BATCH) {
      const batch = want.slice(i, i + BATCH);
      batch.forEach((id) => pending.current.add(`${windowFrom}|${id}`));
      api.guide(windowFrom, windowFrom + DAY, batch)
        .then((g) => { for (const id of batch) byId!.set(id, g.channels[id] || []); })
        .catch(() => { for (const id of batch) byId!.set(id, []); })
        .finally(() => {
          batch.forEach((id) => pending.current.delete(`${windowFrom}|${id}`));
          setGuideVersion((v) => v + 1);
        });
    }
  }, []);

  const onVisible = useCallback((first: number, last: number) => {
    const ids: string[] = [];
    for (let i = first; i <= last && i < shown.length; i++) if (shown[i].epgId) ids.push(shown[i].epgId!);
    if (ids.length) load(from, ids);
  }, [shown, from, load]);

  // The playing channel's guide for today, for "now" in the info block whatever day the guide shows.
  useEffect(() => {
    if (playing?.epgId) load(today, [playing.epgId]);
  }, [playing, today, load]);

  // Every 30 minutes, forget today's guide so a changed schedule shows.
  useEffect(() => {
    const t = setInterval(() => { guide.current.clear(); setGuideVersion((v) => v + 1); }, 30 * 60_000);
    return () => clearInterval(t);
  }, []);

  /** Preview [c] (already playing: left as is, never re-tuned); [full]: also go full screen. */
  function play(c: Channel, full = false) {
    if (playing?.id !== c.id) {
      setPlaying(c);
      localSet('dptv.lastChannel', c.id);
      const nowProg = (programmesFor(c, today) || programmesFor(c, from) || []).find((p) => p.start <= Date.now() && p.stop > Date.now());
      setSelection(nowProg ? { channelId: c.id, start: nowProg.start } : { channelId: c.id, start: 0 });
    }
    // Straight away, while the double click still counts as the user's gesture the browser asks for.
    if (full) enterFullscreen();
  }

  function enterFullscreen() {
    const el = playerBox.current?.querySelector('.player') as HTMLElement | null;
    if (el && !document.fullscreenElement) el.requestFullscreen?.().catch(() => undefined);
  }

  function fullscreen() {
    const el = playerBox.current?.querySelector('.player') as HTMLElement | null;
    if (!el) return;
    if (document.fullscreenElement) document.exitFullscreen().catch(() => undefined);
    else el.requestFullscreen?.().catch(() => undefined);
  }

  // What the info block shows: the selected programme, else what's on now on the playing channel.
  const selChannel = selection ? channels.find((c) => c.id === selection.channelId) || null : playing;
  const selProgs = selChannel
    ? [...(programmesFor(selChannel, from) || []), ...(from !== today ? programmesFor(selChannel, today) || [] : [])]
    : [];
  let selProg: Programme | null = null;
  if (selection?.start) selProg = selProgs.find((p) => p.start === selection.start) || null;
  if (!selProg) selProg = selProgs.find((p) => p.start <= now && p.stop > now) || null;
  const nextProg = selProg && selProg.start <= now && selProg.stop > now
    ? selProgs.filter((p) => p.start >= selProg!.stop).sort((a, b) => a.start - b.start)[0] || null
    : null;

  const days = Array.from({ length: DAYS }, (_, i) => today + i * DAY);
  const catName = query ? `Search: ${query}` : category === ALL ? 'All channels' : category === FAVOURITES ? 'Favourites' : categories.find((c) => c.id === category)?.name || 'Channels';

  return (
    <div className="livetv">
      <section className="top">
        <div ref={playerBox} className="top-player">
          <Player source={playing ? { kind: 'live', url: api.streamUrl(playing.id), title: playing.name } : null} onDoubleClick={fullscreen} />
        </div>
        <InfoBlock
          channel={selChannel}
          programme={selProg}
          next={nextProg}
          now={now}
          playingId={playing?.id ?? null}
          favourite={selChannel ? favourites.has(selChannel.id) : false}
          onFavourite={() => selChannel && toggleFavourite(selChannel.id)}
          onWatch={() => selChannel && play(selChannel)}
          onFullscreen={fullscreen}
          rec={rec}
        />
      </section>

      <div className="toolbar">
        <button className={panelOpen ? 'chip cat-toggle on' : 'chip cat-toggle'} onClick={() => setPanelOpen((o) => !o)} title="Categories">
          <span className="burger" aria-hidden="true">☰</span>
          <span className="cat-toggle-name">{catName}</span>
          <span className="count">{shown.length}</span>
        </button>
        <label className="guide-search">
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" strokeWidth="2" /><path d="M20 20l-4-4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>
          <input type="search" placeholder="Search programs or channels..." value={query} onChange={(e) => onQuery(e.target.value)} aria-label="Search programs or channels" />
        </label>
        <div className="day-chips">
          {days.map((d, i) => (
            <button key={d} className={i === dayOffset ? 'chip day on' : 'chip day'} onClick={() => setDayOffset(i)}>{dayLabel(d, today)}</button>
          ))}
        </div>
        <button className="chip jump-now" onClick={jumpToNow}>Jump to now</button>
      </div>

      <div className="guide-area">
        {panelOpen && (
          <aside className="categories">
            <CatButton on={!query && category === ALL} name="All channels" count={channels.length} onClick={() => setCategory(ALL)} />
            <CatButton on={!query && category === FAVOURITES} name="★ Favourites" count={favourites.size} onClick={() => setCategory(FAVOURITES)} />
            <div className="cat-sep" />
            {categories.map((c) => (
              <CatButton key={c.id} on={!query && category === c.id} name={c.name} count={counts.get(c.id) || 0} onClick={() => { setCategory(c.id); if (window.innerWidth < 1000) setPanelOpen(false); }} />
            ))}
          </aside>
        )}
        <GuideGrid
          channels={shown}
          programmesOf={(c) => programmesFor(c, from)}
          from={from}
          to={to}
          now={now}
          playingId={playing?.id ?? null}
          selection={selection}
          recordingOf={rec.recordingOf}
          onSelect={(c, p) => setSelection({ channelId: c.id, start: p?.start ?? 0 })}
          onPlay={play}
          onVisible={onVisible}
          resetKey={`${category}|${query}|${from}|${jumpKey}`}
        />
      </div>
    </div>
  );
}

function CatButton({ on, name, count, onClick }: { on: boolean; name: string; count: number; onClick: () => void }) {
  return (
    <button className={on ? 'cat on' : 'cat'} onClick={onClick} title={name}>
      <span className="cat-name">{name}</span>
      <span className="count">{count}</span>
    </button>
  );
}

/** The block beside the video: tags, title, time with progress, the channel, the synopsis, the actions. */
function InfoBlock({ channel, programme, next, now, playingId, favourite, onFavourite, onWatch, onFullscreen, rec }: {
  channel: Channel | null;
  programme: Programme | null;
  next: Programme | null;
  now: number;
  playingId: string | null;
  favourite: boolean;
  onFavourite: () => void;
  onWatch: () => void;
  onFullscreen: () => void;
  rec: RecordActions;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => setError(null), [channel?.id, programme?.start]);

  if (!channel) {
    return (
      <div className="info">
        <div className="info-empty">
          <div className="info-title">Live TV</div>
          <p className="muted">Pick a channel to watch, or a programme to see what it is and record it.</p>
        </div>
      </div>
    );
  }

  const live = programme ? programme.start <= now && programme.stop > now : false;
  const past = programme ? programme.stop <= now : false;
  const recording = programme ? rec.recordingOf(channel, programme) : undefined;
  const progress = programme && live ? (now - programme.start) / (programme.stop - programme.start) : 0;
  const isPlaying = playingId === channel.id;

  async function act(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try { await fn(); } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  let recButton: JSX.Element | null = null;
  if (programme) {
    if (!rec.configured) {
      recButton = <button className="btn rec" onClick={rec.openSettings}><span className="rec-glyph" />Record</button>;
    } else if (recording?.status === 'recording') {
      recButton = <button className="btn rec active" disabled={busy} onClick={() => confirm('Stop this recording? What was recorded so far is deleted.') && act(() => rec.remove(recording))}><span className="rec-glyph pulse" />Stop recording</button>;
    } else if (recording?.status === 'scheduled') {
      recButton = <button className="btn rec scheduled" disabled={busy} onClick={() => act(() => rec.remove(recording))}><span className="rec-glyph" />Cancel recording</button>;
    } else if (recording?.status === 'completed') {
      recButton = <button className="btn ghost" disabled>✓ Recorded</button>;
    } else if (past && !channel.catchup) {
      recButton = null;
    } else {
      recButton = (
        <button className="btn rec" disabled={busy} onClick={() => act(() => rec.record(channel, programme))}>
          <span className="rec-glyph" />{past ? 'Record from catch-up' : recording?.status === 'failed' ? 'Record again' : 'Record'}
        </button>
      );
    }
  }

  return (
    <div className="info">
      <div className="tags">
        {live && <span className="tag live">LIVE</span>}
        {recording?.status === 'recording' && <span className="tag outline red"><span className="dot" />RECORDING</span>}
        {recording?.status === 'scheduled' && <span className="tag outline amber">SCHEDULED</span>}
        {recording?.status === 'completed' && <span className="tag outline green">RECORDED</span>}
        {recording?.status === 'failed' && <span className="tag outline red" title={recording.error || ''}>FAILED</span>}
        {past && channel.catchup && <span className="tag outline">CATCH-UP</span>}
      </div>
      <div className="info-title" title={programme?.title || channel.name}>{programme?.title || channel.name}</div>
      {programme ? (
        <div className="info-time">
          <span className="mono">{live ? `${clock(programme.start)} – ${clock(programme.stop)}` : dateTime(programme.start)}</span>
          {live ? (
            <>
              <span className="progress"><span style={{ width: `${Math.round(progress * 100)}%` }} /></span>
              <span className="mono muted">{duration(programme.stop - now)} left</span>
            </>
          ) : <span className="mono muted">{duration(programme.stop - programme.start)}</span>}
        </div>
      ) : <div className="info-time muted">No information available</div>}
      <div className="info-channel">
        <span className="logo-tile small">{channel.logo ? <img src={channel.logo} alt="" /> : <TvGlyph />}</span>
        <span className="info-channel-name">{channel.num != null && <b className="mono">{channel.num} </b>}{channel.name}</span>
      </div>
      {programme?.desc && <p className="desc">{programme.desc}</p>}
      {next && <div className="info-next muted"><span className="mono">Next {clock(next.start)}</span> · {next.title}</div>}
      <div className="actions">
        {(!isPlaying || !programme || live) && (
          isPlaying
            ? <button className="btn primary" onClick={onFullscreen}>⛶ Full screen</button>
            : <button className="btn primary" onClick={onWatch}>▶ Watch</button>
        )}
        {recButton}
        <button className={favourite ? 'btn ghost fav on' : 'btn ghost fav'} onClick={onFavourite} title={favourite ? 'Remove from favourites' : 'Add to favourites'}>{favourite ? '★' : '☆'}</button>
      </div>
      {error && <div className="info-error">{error}</div>}
    </div>
  );
}

function sessionGet(key: string): string | null {
  try { return sessionStorage.getItem(key); } catch { return null; }
}
function sessionSet(key: string, value: string) {
  try { sessionStorage.setItem(key, value); } catch { /* private window */ }
}
export function localGet(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
export function localSet(key: string, value: string) {
  try { localStorage.setItem(key, value); } catch { /* private window */ }
}
