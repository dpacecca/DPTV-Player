import { useEffect, useRef, useState } from 'react';
import mpegts from 'mpegts.js';
import { api } from './api';

/**
 * A live channel: DPTV-Web relays it as one continuous MPEG-TS stream and mpegts.js plays it through Media
 * Source Extensions. Chrome, Edge and Firefox; not Safari, and not HEVC (most 4K) channels.
 */
export function Player({ channelId, title }: { channelId: string | null; title?: string }) {
  const video = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const supported = mpegts.isSupported();

  useEffect(() => {
    setError(null);
    if (!channelId || !video.current || !supported) return;
    setLoading(true);
    const player = mpegts.createPlayer(
      { type: 'mpegts', isLive: true, url: api.streamUrl(channelId) },
      {
        enableWorker: true,
        liveBufferLatencyChasing: true,
        liveBufferLatencyMaxLatency: 6,
        liveBufferLatencyMinRemain: 1.5,
        lazyLoad: false,
        autoCleanupSourceBuffer: true,
      },
    );
    const el = video.current;
    const playing = () => setLoading(false);
    el.addEventListener('playing', playing);
    player.on(mpegts.Events.ERROR, (type: string, detail: string, info: { code?: number; msg?: string }) => {
      setLoading(false);
      if (info?.code === 511 || info?.code === 458) setError('The provider refused another stream: the line\'s connections are all in use.');
      else if (type === mpegts.ErrorTypes.MEDIA_ERROR) setError('This channel\'s format can\'t be played in a browser (often HEVC / 4K).');
      else setError(`The channel can't be played (${detail}${info?.code ? ` ${info.code}` : ''}).`);
    });
    player.attachMediaElement(el);
    player.load();
    Promise.resolve(player.play()).catch(() => { /* autoplay with sound refused: the controls start it */ });
    return () => {
      el.removeEventListener('playing', playing);
      player.pause();
      player.unload();
      player.detachMediaElement();
      player.destroy();
    };
  }, [channelId, supported]);

  return (
    <div className="player">
      <video ref={video} controls playsInline />
      {!channelId && <div className="player-note">Pick a channel to watch</div>}
      {channelId && !supported && <div className="player-note">This browser can't play live TS streams. Use Chrome, Edge or Firefox.</div>}
      {loading && !error && <div className="player-note spinner">Tuning{title ? ` ${title}` : ''}…</div>}
      {error && <div className="player-note error">{error}</div>}
    </div>
  );
}
