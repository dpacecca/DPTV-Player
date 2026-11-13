import { useEffect, useRef, useState } from 'react';
import mpegts from 'mpegts.js';
import { absolute } from './api';
import { formatProblem } from './codecs';
import {
  BUFFER_EVENT, BufferMode, bufferPreset, loadBufferMode, MAX_RECONNECTS, RateMeter, reconnectDelayMs,
  StreamFacts, streamChips,
} from './playback';

export type Source =
  | { kind: 'live'; url: string; title?: string }
  | { kind: 'file'; url: string; type: 'ts' | 'mkv' | 'mp4'; title?: string };

/** This browser's Buffer setting, following a change made in Settings while a channel plays. */
function useBufferMode(): BufferMode {
  const [mode, setMode] = useState<BufferMode>(loadBufferMode);
  useEffect(() => {
    const changed = () => setMode(loadBufferMode());
    window.addEventListener(BUFFER_EVENT, changed);
    return () => window.removeEventListener(BUFFER_EVENT, changed);
  }, []);
  return mode;
}

/**
 * Plays a live channel (one continuous MPEG-TS stream relayed by this container) or a recording from
 * DPTV-Recorder. MPEG-TS goes through mpegts.js (Media Source Extensions: Chrome, Edge, Firefox; not Safari,
 * not HEVC); .mkv / .mp4 recordings play in the browser's own player.
 *
 * A live channel follows this browser's Buffer setting: it gathers that many seconds before it starts, may
 * fall that much further behind live before it jumps forward, and is reconnected by itself when the
 * connection drops (a mobile network does). The stream's details sit over the picture as on the TV.
 */
export function Player({ source, onDoubleClick, autoFocus }: { source: Source | null; onDoubleClick?: () => void; autoFocus?: boolean }) {
  const video = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [reconnecting, setReconnecting] = useState(0);
  // Bumped to open the same live channel again after a drop.
  const [attempt, setAttempt] = useState(0);
  const [facts, setFacts] = useState<StreamFacts>({});
  const bufferMode = useBufferMode();
  const supported = mpegts.isSupported();
  const key = source ? `${source.kind}|${source.url}` : '';

  // A new channel (or recording) starts its reconnect count afresh.
  useEffect(() => { setAttempt(0); setReconnecting(0); }, [key]);

  useEffect(() => {
    setError(null);
    setFacts({});
    const el = video.current;
    if (!source || !el) return;
    setLoading(true);
    const timers: number[] = [];
    let playingSince = 0;
    const playing = () => {
      setLoading(false);
      playingSince = Date.now();
      // Ten steady seconds after a reconnect: the next drop gets the quick retries again.
      timers.push(window.setTimeout(() => { if (playingSince) { setReconnecting(0); } }, 10_000));
    };
    const failed = () => { setLoading(false); setError('This recording can\'t be played in the browser.'); };
    const frameSize = () => setFacts((f) => ({ ...f, width: el.videoWidth || f.width, height: el.videoHeight || f.height }));
    el.addEventListener('playing', playing);
    el.addEventListener('canplay', playing);
    el.addEventListener('loadedmetadata', frameSize);
    el.addEventListener('resize', frameSize);
    const detach = () => {
      timers.forEach((t) => window.clearTimeout(t));
      el.removeEventListener('playing', playing);
      el.removeEventListener('canplay', playing);
      el.removeEventListener('loadedmetadata', frameSize);
      el.removeEventListener('resize', frameSize);
    };

    if (source.kind === 'file' && source.type !== 'ts') {
      el.addEventListener('error', failed);
      el.src = source.url;
      Promise.resolve(el.play()).catch(() => undefined);
      return () => {
        detach();
        el.removeEventListener('error', failed);
        el.pause();
        el.removeAttribute('src');
        el.load();
      };
    }

    if (!supported) { setLoading(false); detach(); return () => undefined; }
    const live = source.kind === 'live';
    const preset = bufferPreset(bufferMode);
    const player = mpegts.createPlayer(
      { type: 'mpegts', isLive: live, url: absolute(source.url) },
      {
        // The loader runs on the page: it keeps the same-origin cookie and resolves every URL as the page does.
        enableWorker: false,
        lazyLoad: !live,
        enableStashBuffer: true,
        stashInitialSize: live ? preset.stashBytes : undefined,
        liveBufferLatencyChasing: live,
        liveBufferLatencyChasingOnPaused: false,
        liveBufferLatencyMaxLatency: preset.maxLatencySec,
        liveBufferLatencyMinRemain: preset.startSec,
        autoCleanupSourceBuffer: true,
        // Keep at least the buffer the setting asks for (mpegts.js trims what lies behind the playhead).
        autoCleanupMaxBackwardDuration: Math.max(30, preset.maxLatencySec * 2),
        autoCleanupMinBackwardDuration: Math.max(15, preset.maxLatencySec),
        seekType: 'range',
      },
    );

    // Stream details: the frame and audio from the stream's own headers, the rate measured as it arrives.
    const meter = new RateMeter(Date.now());
    player.on(mpegts.Events.MEDIA_INFO, (info: mpegts.MSEPlayerMediaInfo) => {
      setFacts((f) => ({
        ...f,
        width: info.width || f.width,
        height: info.height || f.height,
        sarNum: typeof info.sarNum === 'number' ? info.sarNum : f.sarNum,
        sarDen: typeof info.sarDen === 'number' ? info.sarDen : f.sarDen,
        fps: info.fps || f.fps,
        audioChannels: typeof info.audioChannelCount === 'number' ? info.audioChannelCount : f.audioChannels,
      }));
    });
    player.on(mpegts.Events.STATISTICS_INFO, (stats: mpegts.MSEPlayerStatisticsInfo) => {
      if (typeof stats.speed !== 'number') return;
      meter.add(Date.now(), stats.speed);
      const bps = meter.bitsPerSecond();
      if (bps) setFacts((f) => (f.bitsPerSecond && Math.abs(f.bitsPerSecond - bps) < 50_000 ? f : { ...f, bitsPerSecond: bps }));
    });

    let dead = false;
    player.on(mpegts.Events.ERROR, (type: string, detail: string, info: { code?: number; msg?: string }) => {
      if (dead) return;
      setLoading(false);
      // A refusal is an answer, not a dropped connection: say it rather than retry into it.
      if (info?.code === 511 || info?.code === 458) { setError('The provider refused another stream: the line\'s connections are all in use.'); return; }
      if (info?.code === 401) { setError('Signed out. Reload the page and sign in again.'); return; }
      if (type === mpegts.ErrorTypes.MEDIA_ERROR) { setError(formatProblem()); return; }
      if (info?.code && info.code >= 400) { setError(`The stream was refused (${info.code}).`); return; }
      // A live channel whose connection dropped (4G/5G hand-over, Wi-Fi blip): open it again, backing off.
      if (live && type === mpegts.ErrorTypes.NETWORK_ERROR && reconnecting < MAX_RECONNECTS) {
        dead = true;
        const next = reconnecting + 1;
        setReconnecting(next);
        setLoading(true);
        timers.push(window.setTimeout(() => setAttempt((a) => a + 1), reconnectDelayMs(next)));
        return;
      }
      setError(`The ${live ? 'channel' : 'recording'} can't be played (${detail}${info?.msg ? `: ${info.msg}` : ''}).`);
    });
    // The end of a live stream is a drop too: the relay closed it, the channel did not stop airing.
    player.on(mpegts.Events.LOADING_COMPLETE, () => {
      if (!live || dead || reconnecting >= MAX_RECONNECTS) return;
      dead = true;
      const next = reconnecting + 1;
      setReconnecting(next);
      setLoading(true);
      timers.push(window.setTimeout(() => setAttempt((a) => a + 1), reconnectDelayMs(next)));
    });

    player.attachMediaElement(el);
    player.load();
    if (live && preset.startSec > 2) {
      // Gather the setting's seconds first, so a slow connection starts with something in hand.
      const started = Date.now();
      const poll = window.setInterval(() => {
        const ahead = el.buffered.length ? el.buffered.end(el.buffered.length - 1) - el.currentTime : 0;
        // Never wait forever on a stream slower than real time: play after twice the target at most.
        if (ahead >= preset.startSec || Date.now() - started > preset.startSec * 2_000) {
          window.clearInterval(poll);
          Promise.resolve(player.play()).catch(() => { /* autoplay with sound refused: the controls start it */ });
        }
      }, 250);
      timers.push(poll);
    } else {
      Promise.resolve(player.play()).catch(() => { /* autoplay with sound refused: the controls start it */ });
    }
    return () => {
      dead = true;
      detach();
      timers.forEach((t) => window.clearInterval(t));
      try {
        player.pause();
        player.unload();
        player.detachMediaElement();
        player.destroy();
      } catch { /* already torn down */ }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, supported, bufferMode, attempt]);

  useEffect(() => { if (autoFocus) video.current?.focus(); }, [autoFocus]);

  const needsMse = source && (source.kind === 'live' || source.type === 'ts');
  const { ultraHd, chips } = streamChips(facts);
  const tuning = reconnecting > 0 ? 'Reconnecting' : bufferPreset(bufferMode).startSec > 2 && source?.kind === 'live' ? 'Buffering' : 'Tuning';
  return (
    <div className="player" onDoubleClick={onDoubleClick}>
      <video ref={video} controls playsInline />
      {source && !error && (ultraHd || chips.length > 0) && (
        <div className="stream-chips" aria-label="Stream details">
          {ultraHd && <span className="badge-4k">4K</span>}
          {chips.map((c) => <span key={c} className="spec-chip">{c}</span>)}
        </div>
      )}
      {!source && <div className="player-note"><span className="muted">Pick a channel to watch</span></div>}
      {needsMse && !supported && <div className="player-note error">This browser can't play MPEG-TS streams. Use Chrome, Edge or Firefox.</div>}
      {loading && !error && <div className="player-note"><span className="spinner" />{source?.title ? <span>{tuning} {source.title}</span> : null}</div>}
      {error && <div className="player-note error">{error}</div>}
    </div>
  );
}
