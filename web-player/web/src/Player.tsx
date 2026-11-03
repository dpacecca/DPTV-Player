import { useEffect, useRef, useState } from 'react';
import mpegts from 'mpegts.js';
import { absolute } from './api';

export type Source =
  | { kind: 'live'; url: string; title?: string }
  | { kind: 'file'; url: string; type: 'ts' | 'mkv' | 'mp4'; title?: string };

/**
 * Plays a live channel (one continuous MPEG-TS stream relayed by this container) or a recording from
 * DPTV-Recorder. MPEG-TS goes through mpegts.js (Media Source Extensions: Chrome, Edge, Firefox; not Safari,
 * not HEVC); .mkv / .mp4 recordings play in the browser's own player.
 */
export function Player({ source, onDoubleClick, autoFocus }: { source: Source | null; onDoubleClick?: () => void; autoFocus?: boolean }) {
  const video = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const supported = mpegts.isSupported();
  const key = source ? `${source.kind}|${source.url}` : '';

  useEffect(() => {
    setError(null);
    const el = video.current;
    if (!source || !el) return;
    setLoading(true);
    const playing = () => setLoading(false);
    const failed = () => { setLoading(false); setError('This recording can\'t be played in the browser.'); };
    el.addEventListener('playing', playing);
    el.addEventListener('canplay', playing);

    if (source.kind === 'file' && source.type !== 'ts') {
      el.addEventListener('error', failed);
      el.src = source.url;
      Promise.resolve(el.play()).catch(() => undefined);
      return () => {
        el.removeEventListener('playing', playing);
        el.removeEventListener('canplay', playing);
        el.removeEventListener('error', failed);
        el.pause();
        el.removeAttribute('src');
        el.load();
      };
    }

    if (!supported) { setLoading(false); return () => undefined; }
    const live = source.kind === 'live';
    const player = mpegts.createPlayer(
      { type: 'mpegts', isLive: live, url: absolute(source.url) },
      {
        // The loader runs on the page: it keeps the same-origin cookie and resolves every URL as the page does.
        enableWorker: false,
        lazyLoad: !live,
        liveBufferLatencyChasing: live,
        liveBufferLatencyMaxLatency: 6,
        liveBufferLatencyMinRemain: 1.5,
        autoCleanupSourceBuffer: true,
        seekType: 'range',
      },
    );
    player.on(mpegts.Events.ERROR, (type: string, detail: string, info: { code?: number; msg?: string }) => {
      setLoading(false);
      if (info?.code === 511 || info?.code === 458) setError('The provider refused another stream: the line\'s connections are all in use.');
      else if (info?.code === 401) setError('Signed out. Reload the page and sign in again.');
      else if (type === mpegts.ErrorTypes.MEDIA_ERROR) setError('This channel\'s format can\'t be played in a browser (often HEVC / 4K).');
      else if (info?.code && info.code >= 400) setError(`The stream was refused (${info.code}).`);
      else setError(`The ${live ? 'channel' : 'recording'} can't be played (${detail}${info?.msg ? `: ${info.msg}` : ''}).`);
    });
    player.attachMediaElement(el);
    player.load();
    Promise.resolve(player.play()).catch(() => { /* autoplay with sound refused: the controls start it */ });
    return () => {
      el.removeEventListener('playing', playing);
      el.removeEventListener('canplay', playing);
      try {
        player.pause();
        player.unload();
        player.detachMediaElement();
        player.destroy();
      } catch { /* already torn down */ }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, supported]);

  useEffect(() => { if (autoFocus) video.current?.focus(); }, [autoFocus]);

  const needsMse = source && (source.kind === 'live' || source.type === 'ts');
  return (
    <div className="player" onDoubleClick={onDoubleClick}>
      <video ref={video} controls playsInline />
      {!source && <div className="player-note"><span className="muted">Pick a channel to watch</span></div>}
      {needsMse && !supported && <div className="player-note error">This browser can't play MPEG-TS streams. Use Chrome, Edge or Firefox.</div>}
      {loading && !error && <div className="player-note"><span className="spinner" />{source?.title ? <span>Tuning {source.title}</span> : null}</div>}
      {error && <div className="player-note error">{error}</div>}
    </div>
  );
}
