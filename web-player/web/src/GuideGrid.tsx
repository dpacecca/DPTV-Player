import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Channel, Programme, Recording } from './api';
import { clock, duration, HALF_HOUR, MIN } from './time';

const PX_PER_MIN = 7;
export const ROW_H = 64;
const RULER_H = 36;
const OVERSCAN = 6;

export type Selection = { channelId: string; start: number } | null;

/**
 * The TV guide, as on DPTV-Player for Android: the CHANNEL column (logo tile, name, "CH n"), programmes along
 * a timeline with the now-line, the running programme lighter, a red dot on recordings. One click on a channel or
 * programme starts that channel in the preview (and shows the programme's details); a double click goes full
 * screen. Only the rows in view
 * are drawn, so a list of thousands of channels scrolls smoothly; [onVisible] says which rows those are so
 * the page can load their guide.
 */
export function GuideGrid({ channels, programmesOf, from, to, now, playingId, selection, recordingOf, onSelect, onPlay, onVisible, resetKey }: {
  channels: Channel[];
  programmesOf: (c: Channel) => Programme[] | undefined;
  from: number;
  to: number;
  now: number;
  playingId: string | null;
  selection: Selection;
  recordingOf: (c: Channel, p: Programme) => Recording | undefined;
  onSelect: (c: Channel, p: Programme | null) => void;
  onPlay: (c: Channel, full?: boolean) => void;
  onVisible: (first: number, last: number) => void;
  /** Changes when the list or the day changes: scroll back to the top / to "now". */
  resetKey: string;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewH, setViewH] = useState(600);
  const [labelW, setLabelW] = useState(() => (window.innerWidth < 700 ? 132 : 280));
  const width = ((to - from) / MIN) * PX_PER_MIN;
  const x = (t: number) => ((Math.min(Math.max(t, from), to) - from) / MIN) * PX_PER_MIN;
  const slots: number[] = [];
  for (let t = from; t < to; t += HALF_HOUR) slots.push(t);

  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const ro = new ResizeObserver(() => {
      setViewH(el.clientHeight);
      setLabelW(el.clientWidth < 700 ? 132 : 280);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // New list or new day: back to the top, the timeline on "now" (a little of the past showing) or the day's start.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    el.scrollTop = 0;
    setScrollTop(0);
    el.scrollLeft = now >= from && now < to ? Math.max(0, x(now) - 30 * PX_PER_MIN) : 0;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetKey]);

  const first = Math.max(0, Math.floor(scrollTop / ROW_H) - OVERSCAN);
  const last = Math.min(channels.length - 1, Math.ceil((scrollTop + viewH) / ROW_H) + OVERSCAN);
  useEffect(() => { onVisible(first, last); }, [first, last, channels, onVisible]);

  const rows = [];
  for (let i = first; i <= last; i++) {
    const c = channels[i];
    const progs = programmesOf(c);
    const playing = playingId === c.id;
    rows.push(
      <div key={c.id} className={playing ? 'g-row playing' : 'g-row'} style={{ top: i * ROW_H, height: ROW_H }}>
        <button
          className={selection?.channelId === c.id ? 'g-label on' : 'g-label'}
          style={{ width: labelW }}
          onClick={() => onPlay(c)}
          onDoubleClick={() => onPlay(c, true)}
          title={c.name}
        >
          <span className="logo-tile">{c.logo ? <img src={c.logo} alt="" loading="lazy" /> : <TvGlyph />}</span>
          <span className="g-label-text">
            <span className="g-name">{c.name}</span>
            <span className="g-num">
              {c.num != null && <>CH {c.num}</>}
              {c.catchup && <span className="g-catchup" title="Catch-up">⟲</span>}
              {playing && <span className="g-onair">▶</span>}
            </span>
          </span>
        </button>
        <div className="g-strip" style={{ width }}>
          {progs === undefined && <div className="g-cell loading" style={{ left: 0, width: width - 4 }} />}
          {progs !== undefined && progs.length === 0 && (
            <button className="g-cell empty" style={{ left: 0, width: width - 4 }} onClick={() => { onPlay(c); onSelect(c, null); }} onDoubleClick={() => onPlay(c, true)}>
              <span className="g-title">No information available</span>
            </button>
          )}
          {progs?.map((p) => {
            const left = x(p.start);
            const w = x(p.stop) - left;
            if (w <= 3) return null;
            const live = p.start <= now && p.stop > now;
            const rec = recordingOf(c, p);
            const selected = selection?.channelId === c.id && selection.start === p.start;
            const cls = ['g-cell', live ? 'live' : p.stop <= now ? 'past' : '', selected ? 'on' : ''].join(' ');
            return (
              <button
                key={p.start}
                className={cls}
                style={{ left, width: w - 4 }}
                onClick={() => { onPlay(c); onSelect(c, p); }}
                onDoubleClick={() => onPlay(c, true)}
                title={`${p.title}\n${clock(p.start)} – ${clock(p.stop)}`}
              >
                <span className="g-title">
                  {rec && (rec.status === 'recording' || rec.status === 'scheduled' || rec.status === 'completed') && (
                    <span className={`rec-dot ${rec.status}`} title={rec.status} />
                  )}
                  {p.title}
                </span>
                <span className="g-time">{clock(p.start)} · {duration(p.stop - p.start)}</span>
              </button>
            );
          })}
        </div>
      </div>,
    );
  }

  return (
    <div
      className="guide"
      ref={scroller}
      onScroll={(e) => {
        const top = (e.currentTarget as HTMLDivElement).scrollTop;
        if (Math.abs(top - scrollTop) >= ROW_H / 2) setScrollTop(top);
      }}
    >
      <div className="guide-inner" style={{ width: labelW + width }}>
        <div className="ruler" style={{ height: RULER_H }}>
          <div className="ruler-corner" style={{ width: labelW }}>CHANNEL</div>
          {slots.map((t) => <div key={t} className="slot" style={{ width: 30 * PX_PER_MIN }}>{clock(t)}</div>)}
          {now >= from && now < to && <div className="ruler-now" style={{ left: labelW + x(now) }} />}
        </div>
        <div className="g-rows" style={{ height: channels.length * ROW_H }}>
          {rows}
          {now >= from && now < to && <div className="now-line" style={{ left: labelW + x(now) }} />}
        </div>
        {channels.length === 0 && <div className="g-none muted">No channels here.</div>}
      </div>
    </div>
  );
}

export function TvGlyph() {
  return (
    <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" className="tv-glyph">
      <rect x="3" y="5" width="18" height="12" rx="2" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <path d="M8 20h8" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}
