/**
 * Playback settings that belong to this browser (a phone on 4G wants a different buffer from a desktop on
 * cable), and the stream-detail chips the player shows, built the way DPTV-Player's are on the TV.
 */

/** How much the live player keeps in hand before it plays, and how far behind live it may fall. */
export type BufferMode = 'low' | 'balanced' | 'mobile' | 'max';

export interface BufferPreset {
  mode: BufferMode;
  label: string;
  hint: string;
  /** Seconds of video gathered before a live channel starts, and kept after a jump back to live. */
  startSec: number;
  /** Fall further behind live than this and the player jumps forward (to [startSec] behind). */
  maxLatencySec: number;
  /** Bytes the loader gathers before handing them on: bigger rides out short dips on a mobile network. */
  stashBytes: number;
}

export const BUFFER_PRESETS: BufferPreset[] = [
  { mode: 'low', label: 'Low latency', hint: 'Closest to live. For a steady wired or Wi-Fi connection.', startSec: 1.5, maxLatencySec: 6, stashBytes: 384 * 1024 },
  { mode: 'balanced', label: 'Balanced', hint: 'A few seconds behind live, fewer stalls on a busy Wi-Fi.', startSec: 4, maxLatencySec: 15, stashBytes: 1024 * 1024 },
  { mode: 'mobile', label: 'Mobile data (4G/5G)', hint: 'Rides out the dips of a mobile connection; about 10 s behind live.', startSec: 10, maxLatencySec: 40, stashBytes: 3 * 1024 * 1024 },
  { mode: 'max', label: 'Maximum', hint: 'For a poor connection: about 20 s behind live, starts slowest.', startSec: 20, maxLatencySec: 75, stashBytes: 6 * 1024 * 1024 },
];

export const DEFAULT_BUFFER: BufferMode = 'low';
const BUFFER_KEY = 'dptv.buffer';

export function bufferPreset(mode: BufferMode | string | null | undefined): BufferPreset {
  return BUFFER_PRESETS.find((p) => p.mode === mode) ?? BUFFER_PRESETS.find((p) => p.mode === DEFAULT_BUFFER)!;
}

/** This browser's choice; the default when none was made or storage is unavailable (a private window). */
export function loadBufferMode(): BufferMode {
  try { return bufferPreset(localStorage.getItem(BUFFER_KEY)).mode; } catch { return DEFAULT_BUFFER; }
}

/** Event the player listens for, so a new choice applies to the channel on screen at once. */
export const BUFFER_EVENT = 'dptv:buffer';

export function saveBufferMode(mode: BufferMode) {
  try { localStorage.setItem(BUFFER_KEY, mode); } catch { /* private window: the choice lasts for this page only */ }
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(BUFFER_EVENT, { detail: mode }));
}

/** How many times a dropped live stream is reconnected in a row before the player gives up and says so. */
export const MAX_RECONNECTS = 6;

/** Wait before the [attempt]th reconnect (1-based): quick at first, then backing off, at most 10 s. */
export function reconnectDelayMs(attempt: number): number {
  return Math.min(10_000, 1_000 * 2 ** Math.max(0, attempt - 1));
}

// --- Stream details -----------------------------------------------------------------------------

/** What the player knows about the stream on screen; every field may be unknown. */
export interface StreamFacts {
  width?: number;
  height?: number;
  /** Pixel aspect (sample aspect ratio), for anamorphic SD: the picture is shown [sarNum]/[sarDen] wider. */
  sarNum?: number;
  sarDen?: number;
  fps?: number;
  /** Measured stream rate, bits per second. */
  bitsPerSecond?: number;
  audioChannels?: number;
}

/** "1080p", "720p", "4K" … from the frame size. The TV's rule: a cropped cinemascope frame counts by width. */
export function resolutionLabel(width: number | undefined, height: number | undefined): string | null {
  if (!height || height <= 0) return null;
  const effective = width && width > 0 ? Math.max(height, Math.floor((width * 9) / 16)) : height;
  if (effective >= 2160) return '4K';
  if (effective >= 1440) return '1440p';
  if (effective >= 1080) return '1080p';
  if (effective >= 720) return '720p';
  if (effective >= 480) return '480p';
  return `${height}p`;
}

/** The shape the picture is shown in, by the TV's rule: "16:9", "4:3", "21:9", "16:10", else "2.35:1". */
export function aspectLabel(width: number, height: number): string | null {
  if (width <= 0 || height <= 0) return null;
  const r = width / height;
  if (r >= 1.72 && r <= 1.82) return '16:9';
  if (r >= 1.28 && r <= 1.40) return '4:3';
  if (r >= 2.15) return '21:9';
  if (r >= 1.55 && r <= 1.66) return '16:10';
  return `${r.toFixed(2)}:1`;
}

function channelsLabel(n: number | undefined): string | null {
  switch (n) {
    case 1: return 'MONO';
    case 2: return 'STEREO';
    case 6: return '5.1';
    case 8: return '7.1';
    default: return null;
  }
}

/**
 * The chips under the picture, in the TV's order: aspect · resolution · FPS · Mbps · audio. A 4K picture
 * is not a chip but the gold badge ahead of them ([ultraHd]), as on the TV, so it is never shown twice.
 */
export function streamChips(f: StreamFacts): { ultraHd: boolean; chips: string[] } {
  const chips: string[] = [];
  const w = f.width ?? 0;
  const h = f.height ?? 0;
  if (w > 0 && h > 0) {
    const sar = f.sarNum && f.sarDen ? f.sarNum / f.sarDen : 1;
    const aspect = aspectLabel(Math.round(w * sar), h);
    if (aspect) chips.push(aspect);
  }
  const res = resolutionLabel(w, h);
  const ultraHd = res === '4K';
  if (res && !ultraHd) chips.push(res);
  if (f.fps && f.fps > 0) chips.push(`${Math.round(f.fps)} FPS`);
  if (f.bitsPerSecond && f.bitsPerSecond > 0) chips.push(`${(f.bitsPerSecond / 1_000_000).toFixed(1)} Mbps`);
  const audio = channelsLabel(f.audioChannels);
  if (audio) chips.push(audio);
  return { ultraHd, chips };
}

/**
 * The stream's rate from what has arrived over the last [windowMs]: mpegts.js reports the loader's speed
 * every second, and a live stream arrives at its own bitrate once the buffer is full. The first seconds
 * after a tune fill the buffer faster than real time, so they are left out ([settleMs]).
 */
export class RateMeter {
  private samples: { at: number; kbps: number }[] = [];
  private readonly startedAt: number;
  private readonly windowMs: number;
  private readonly settleMs: number;

  constructor(now: number, windowMs = 10_000, settleMs = 5_000) {
    this.startedAt = now;
    this.windowMs = windowMs;
    this.settleMs = settleMs;
  }

  /** One speed report, in KB/s (1 KB = 1024 bytes, as mpegts.js counts). */
  add(now: number, kiloBytesPerSecond: number) {
    if (now - this.startedAt < this.settleMs || !(kiloBytesPerSecond >= 0)) return;
    this.samples.push({ at: now, kbps: kiloBytesPerSecond });
    this.samples = this.samples.filter((s) => now - s.at <= this.windowMs);
  }

  /** Bits per second, or undefined until there is something to say. */
  bitsPerSecond(): number | undefined {
    if (this.samples.length < 3) return undefined;
    const avg = this.samples.reduce((sum, s) => sum + s.kbps, 0) / this.samples.length;
    return avg > 0 ? avg * 1024 * 8 : undefined;
  }
}
