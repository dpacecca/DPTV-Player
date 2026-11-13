export type Category = { id: string; name: string };
export type Channel = { id: string; num: number | null; name: string; logo: string | null; categoryId: string | null; epgId: string | null; catchup: boolean };
export type Programme = { start: number; stop: number; title: string; desc: string };
export type Guide = { from: number; to: number; channels: Record<string, Programme[]> };

/** The recorder's word for a recording. */
export type RecordingStatus = 'scheduled' | 'recording' | 'completed' | 'failed' | 'cancelled';
export type Recording = {
  id: number;
  channelId: string;
  channelName: string;
  title: string;
  start: number;
  stop: number;
  recStart: number;
  recEnd: number;
  status: RecordingStatus;
  kind: 'live' | 'catchup';
  error: string | null;
  filename: string | null;
};
export type RecorderConfig = { configured: boolean; source: 'user' | 'server' | 'dptv' | null; url: string };
export type Playback = { url: string; type: 'ts' | 'mkv' | 'mp4'; growing: boolean };

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, { credentials: 'same-origin', ...init });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, (body as { error?: string }).error || `Error ${res.status}`);
  return body as T;
}

const json = (method: string, body: unknown): RequestInit => ({
  method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});

/** An absolute URL: mpegts.js loads in a Web Worker, where a path like "/api/…" can't be resolved. */
export const absolute = (path: string) => new URL(path, window.location.href).toString();

export const api = {
  config: () => call<{ serverFixed: boolean; version: string; sso: { label: string; only: boolean } | null }>('/config'),
  me: () => call<{ username: string; server: string | null; sso: { name: string } | null }>('/me'),
  ssoPending: () => call<{ name: string } | null>('/auth/pending'),
  ssoLink: (server: string, username: string, password: string) =>
    call<{ username: string }>('/auth/link', json('POST', { server, username, password })),
  ssoUnlink: () => call<{ ok: boolean }>('/auth/unlink', { method: 'POST' }),
  login: (server: string, username: string, password: string) =>
    call<{ username: string }>('/login', json('POST', { server, username, password })),
  logout: () => call<{ ok: boolean }>('/logout', { method: 'POST' }),
  categories: () => call<Category[]>('/live/categories'),
  channels: () => call<Channel[]>('/live/channels'),
  guide: (from: number, to: number, ids: string[]) => call<Guide>('/guide', json('POST', { from, to, ids })),
  streamUrl: (id: string) => absolute(`/api/live/${encodeURIComponent(id)}/stream`),

  dvrConfig: () => call<RecorderConfig>('/dvr/config'),
  dvrSetConfig: (url: string, apiKey: string) => call<RecorderConfig & { recordings: number }>('/dvr/config', json('PUT', { url, apiKey })),
  dvrClearConfig: () => call<RecorderConfig>('/dvr/config', { method: 'DELETE' }),
  recordings: () => call<Recording[]>('/dvr/recordings'),
  record: (c: Channel, p: Programme) =>
    call<Recording>('/dvr/recordings', json('POST', { channelId: c.id, channelName: c.name, title: p.title, start: p.start, stop: p.stop })),
  /** Cancels a scheduled/running recording, or deletes a finished one with its file. */
  removeRecording: (r: Recording) =>
    call<{ ok: boolean }>(`/dvr/recordings/${r.id}${['completed', 'failed', 'cancelled'].includes(r.status) ? `?status=${r.status}` : ''}`, { method: 'DELETE' }),
  playback: (id: number) => call<Playback>(`/dvr/recordings/${id}/play`),
};
