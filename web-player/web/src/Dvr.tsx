import { useEffect, useMemo, useState } from 'react';
import { api, Channel, Playback, Recording, RecordingStatus } from './api';
import { TvGlyph } from './GuideGrid';
import { Player } from './Player';
import { dateTime, duration } from './time';

type Filter = 'all' | 'completed' | 'upcoming' | 'failed';
type Sort = 'date' | 'az';

const STATUS: Record<RecordingStatus, { label: string; cls: string }> = {
  scheduled: { label: 'SCHEDULED', cls: 'amber' },
  recording: { label: 'RECORDING', cls: 'red' },
  completed: { label: 'RECORDED', cls: 'green' },
  failed: { label: 'FAILED', cls: 'red' },
  cancelled: { label: 'CANCELLED', cls: '' },
};

/**
 * The DVR, as on DPTV-Player for Android: recordings on DPTV-Recorder, newest first or A–Z, with what's
 * scheduled and running. Click a recording to play it here (seekable); cancel, stop or delete it.
 */
export function Dvr({ recordings, error, configured, channels, onRemove, onRefresh, openSettings }: {
  recordings: Recording[] | null;
  error: string | null;
  configured: boolean;
  channels: Channel[];
  onRemove: (r: Recording) => Promise<void>;
  onRefresh: () => void;
  openSettings: () => void;
}) {
  const [filter, setFilter] = useState<Filter>('all');
  const [sort, setSort] = useState<Sort>('date');
  const [watching, setWatching] = useState<{ r: Recording; p: Playback } | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const logos = useMemo(() => new Map(channels.map((c) => [c.id, c.logo])), [channels]);

  useEffect(() => { onRefresh(); }, [onRefresh]);
  useEffect(() => {
    if (!watching) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setWatching(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [watching]);

  if (!configured) {
    return (
      <div className="dvr">
        <div className="dvr-setup">
          <div className="info-title">DVR</div>
          <p className="muted">
            Recordings are made by your DPTV-Recorder server, so they happen whether this page is open or not -
            the same server DPTV-Player on your TV records to. Add its address and an API key (DPTV-Recorder →
            Settings → Account → API keys) to schedule, watch and manage recordings here.
          </p>
          <button className="btn primary" onClick={openSettings}>Set up recording</button>
        </div>
      </div>
    );
  }

  const list = (recordings || [])
    .filter((r) => r.status !== 'cancelled')
    .filter((r) => filter === 'all'
      || (filter === 'completed' && r.status === 'completed')
      || (filter === 'upcoming' && (r.status === 'scheduled' || r.status === 'recording'))
      || (filter === 'failed' && r.status === 'failed'))
    .sort((a, b) => (sort === 'az' ? a.title.localeCompare(b.title) || b.start - a.start : b.start - a.start));
  const count = (f: (r: Recording) => boolean) => (recordings || []).filter(f).length;

  async function play(r: Recording) {
    setActionError(null);
    setBusy(r.id);
    try {
      setWatching({ r, p: await api.playback(r.id) });
    } catch (e) {
      setActionError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function remove(r: Recording) {
    const q = r.status === 'scheduled' ? `Cancel the recording of "${r.title}"?`
      : r.status === 'recording' ? `Stop recording "${r.title}"? What was recorded so far is deleted.`
        : `Delete "${r.title}"? The file is removed from DPTV-Recorder.`;
    if (!window.confirm(q)) return;
    setActionError(null);
    setBusy(r.id);
    try { await onRemove(r); } catch (e) { setActionError((e as Error).message); } finally { setBusy(null); }
  }

  return (
    <div className="dvr">
      <div className="toolbar">
        <div className="day-chips">
          <button className={filter === 'all' ? 'chip on' : 'chip'} onClick={() => setFilter('all')}>All <span className="count">{count((r) => r.status !== 'cancelled')}</span></button>
          <button className={filter === 'completed' ? 'chip on' : 'chip'} onClick={() => setFilter('completed')}>Recorded <span className="count">{count((r) => r.status === 'completed')}</span></button>
          <button className={filter === 'upcoming' ? 'chip on' : 'chip'} onClick={() => setFilter('upcoming')}>Scheduled <span className="count">{count((r) => r.status === 'scheduled' || r.status === 'recording')}</span></button>
          <button className={filter === 'failed' ? 'chip on' : 'chip'} onClick={() => setFilter('failed')}>Failed <span className="count">{count((r) => r.status === 'failed')}</span></button>
        </div>
        <div className="toolbar-end">
          <button className={sort === 'date' ? 'chip on' : 'chip'} onClick={() => setSort('date')}>Date</button>
          <button className={sort === 'az' ? 'chip on' : 'chip'} onClick={() => setSort('az')}>A–Z</button>
          <button className="chip" onClick={onRefresh} title="Refresh">⟳</button>
        </div>
      </div>
      {(error || actionError) && <div className="banner error">{actionError || error}</div>}
      {recordings === null && !error && <div className="dvr-empty muted">Loading recordings…</div>}
      {recordings !== null && list.length === 0 && <div className="dvr-empty muted">No recordings here yet. Pick a programme in Live TV and press Record.</div>}
      <div className="dvr-grid">
        {list.map((r) => {
          const s = STATUS[r.status] || STATUS.failed;
          const logo = logos.get(r.channelId);
          const playable = r.status === 'completed' || r.status === 'recording';
          return (
            <div key={r.id} className={`dvr-card ${r.status}`}>
              <button className="dvr-thumb" disabled={!playable || busy === r.id} onClick={() => play(r)} title={playable ? 'Play' : ''}>
                {logo ? <img src={logo} alt="" loading="lazy" /> : <TvGlyph />}
                {playable && <span className="dvr-play">▶</span>}
                <span className={`tag ${r.status === 'recording' ? 'live' : `outline ${s.cls}`} dvr-status`}>
                  {r.status === 'recording' && <span className="dot" />}{s.label}
                </span>
              </button>
              <div className="dvr-body">
                <div className="dvr-title" title={r.title}>{r.title}</div>
                <div className="muted dvr-channel">{r.channelName}</div>
                <div className="mono muted dvr-when">{dateTime(r.start)} · {duration(r.stop - r.start)}{r.kind === 'catchup' ? ' · catch-up' : ''}</div>
                {r.status === 'failed' && r.error && <div className="dvr-error" title={r.error}>{r.error}</div>}
                <div className="dvr-actions">
                  {playable && <button className="btn primary small" disabled={busy === r.id} onClick={() => play(r)}>▶ Play</button>}
                  <button className="btn ghost small danger" disabled={busy === r.id} onClick={() => remove(r)}>
                    {r.status === 'scheduled' ? 'Cancel' : r.status === 'recording' ? 'Stop' : 'Delete'}
                  </button>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {watching && (
        <div className="modal-back" onClick={() => setWatching(null)}>
          <div className="modal watch-modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <div>
                <div className="modal-title">{watching.r.title}</div>
                <div className="muted mono">{watching.r.channelName} · {dateTime(watching.r.start)}{watching.p.growing ? ' · still recording' : ''}</div>
              </div>
              <button className="btn ghost" onClick={() => setWatching(null)} aria-label="Close">✕</button>
            </div>
            <Player source={{ kind: 'file', url: watching.p.url, type: watching.p.type, title: watching.r.title }} autoFocus />
          </div>
        </div>
      )}
    </div>
  );
}
