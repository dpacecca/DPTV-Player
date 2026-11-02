import { useEffect, useRef } from 'react';
import { Channel, Guide } from './api';
import { clock, HALF_HOUR, MIN } from './time';

const PX_PER_MIN = 6;
const ROW_H = 56;
const LABEL_W = 220;

/** The TV guide: one row per channel, programmes laid along a timeline, the now-line, click to watch. */
export function GuideGrid({ channels, guide, from, to, now, playingId, onPlay }: {
  channels: Channel[];
  guide: Guide | null;
  from: number;
  to: number;
  now: number;
  playingId: string | null;
  onPlay: (c: Channel) => void;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const width = ((to - from) / MIN) * PX_PER_MIN;
  const x = (t: number) => ((Math.max(t, from) - from) / MIN) * PX_PER_MIN;
  const slots: number[] = [];
  for (let t = from; t < to; t += HALF_HOUR) slots.push(t);

  // Open on "now", a little of the past showing.
  useEffect(() => {
    if (scroller.current) scroller.current.scrollLeft = Math.max(0, x(now) - 15 * PX_PER_MIN);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [from]);

  return (
    <div className="guide" ref={scroller}>
      <div className="guide-inner" style={{ width: LABEL_W + width }}>
        <div className="ruler">
          <div className="ruler-corner" style={{ width: LABEL_W }} />
          {slots.map((t) => <div key={t} className="slot" style={{ width: 30 * PX_PER_MIN }}>{clock(t)}</div>)}
        </div>
        {channels.map((c) => {
          const progs = (c.epgId && guide?.channels[c.epgId]) || [];
          return (
            <div key={c.id} className={playingId === c.id ? 'row on' : 'row'} style={{ height: ROW_H }}>
              <button className="row-label" style={{ width: LABEL_W }} onClick={() => onPlay(c)} title={c.name}>
                {c.logo ? <img src={c.logo} alt="" loading="lazy" /> : <span className="logo-blank" />}
                <span>{c.num != null && <b>{c.num} </b>}{c.name}</span>
              </button>
              <div className="row-strip" style={{ width }}>
                {progs.length === 0 && <div className="prog empty" style={{ left: 0, width }}>{guide ? 'No guide' : ''}</div>}
                {progs.map((p) => {
                  const left = x(p.start);
                  const w = x(Math.min(p.stop, to)) - left;
                  if (w <= 2) return null;
                  const live = p.start <= now && p.stop > now;
                  return (
                    <button
                      key={p.start}
                      className={live ? 'prog live' : p.stop <= now ? 'prog past' : 'prog'}
                      style={{ left, width: w - 3 }}
                      onClick={() => onPlay(c)}
                      title={`${p.title}\n${clock(p.start)}–${clock(p.stop)}${p.desc ? `\n\n${p.desc}` : ''}`}
                    >
                      <span className="prog-title">{p.title}</span>
                      <span className="prog-time">{clock(p.start)}</span>
                    </button>
                  );
                })}
              </div>
            </div>
          );
        })}
        <div className="now-line" style={{ left: LABEL_W + x(now) }} />
      </div>
    </div>
  );
}
