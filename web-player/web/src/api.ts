export type Category = { id: string; name: string };
export type Channel = { id: string; num: number | null; name: string; logo: string | null; categoryId: string | null; epgId: string | null; catchup: boolean };
export type Programme = { start: number; stop: number; title: string; desc: string };
export type Guide = { from: number; to: number; channels: Record<string, Programme[]> };

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, { credentials: 'same-origin', ...init });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, (body as { error?: string }).error || `Error ${res.status}`);
  return body as T;
}

export const api = {
  config: () => call<{ serverFixed: boolean; version: string; sso: { label: string; only: boolean } | null }>('/config'),
  me: () => call<{ username: string; server: string | null; sso: { name: string } | null }>('/me'),
  ssoPending: () => call<{ name: string } | null>('/auth/pending'),
  ssoLink: (server: string, username: string, password: string) =>
    call<{ username: string }>('/auth/link', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ server, username, password }) }),
  ssoUnlink: () => call<{ ok: boolean }>('/auth/unlink', { method: 'POST' }),
  login: (server: string, username: string, password: string) =>
    call<{ username: string }>('/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ server, username, password }) }),
  logout: () => call<{ ok: boolean }>('/logout', { method: 'POST' }),
  categories: () => call<Category[]>('/live/categories'),
  channels: () => call<Channel[]>('/live/channels'),
  guide: (from: number, to: number) => call<Guide>(`/guide?from=${from}&to=${to}`),
  streamUrl: (id: string) => `/api/live/${encodeURIComponent(id)}/stream`,
};
