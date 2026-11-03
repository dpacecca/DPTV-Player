/**
 * What this browser can decode through Media Source Extensions, which is what mpegts.js feeds. mpegts.js
 * unpacks H.264, HEVC (H.265), AAC, MP3, AC-3 and E-AC-3 from the stream; whether a channel then plays is
 * down to the browser's own decoders (HEVC: hardware decoding in Chrome / Edge, Safari on a Mac).
 */
export type Codecs = { h264: boolean; hevc: boolean; hevc10: boolean; ac3: boolean; eac3: boolean };

let cached: Codecs | null = null;

export function browserCodecs(): Codecs {
  if (cached) return cached;
  const ms = (window as unknown as { MediaSource?: typeof MediaSource; ManagedMediaSource?: typeof MediaSource }).MediaSource
    || (window as unknown as { ManagedMediaSource?: typeof MediaSource }).ManagedMediaSource;
  const ok = (type: string) => {
    try { return Boolean(ms?.isTypeSupported(type)); } catch { return false; }
  };
  cached = {
    h264: ok('video/mp4; codecs="avc1.640028"'),
    hevc: ok('video/mp4; codecs="hvc1.1.6.L150.B0"') || ok('video/mp4; codecs="hev1.1.6.L150.B0"'),
    hevc10: ok('video/mp4; codecs="hvc1.2.4.L153.B0"') || ok('video/mp4; codecs="hev1.2.4.L153.B0"'),
    ac3: ok('audio/mp4; codecs="ac-3"'),
    eac3: ok('audio/mp4; codecs="ec-3"'),
  };
  return cached;
}

/** Why a channel's format failed, in this browser's terms. */
export function formatProblem(): string {
  const c = browserCodecs();
  const missing = [
    !c.hevc && 'HEVC (most 4K)',
    c.hevc && !c.hevc10 && '10-bit HEVC (4K HDR)',
    !c.ac3 && 'AC-3',
    !c.eac3 && 'E-AC-3 (Dolby Digital Plus)',
  ].filter(Boolean);
  return missing.length
    ? `This channel's format can't be decoded by this browser. It can't play ${missing.join(', ')} here - see Settings for how to get them.`
    : 'This channel\'s format can\'t be played in this browser.';
}
