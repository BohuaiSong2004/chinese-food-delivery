// API client. The access token lives only in memory; the refresh token is an
// httpOnly cookie scoped to /api/auth, so script on the page can never read it.

export type Role = 'CUSTOMER' | 'MERCHANT' | 'RIDER' | 'ADMIN';
export interface User { id: number; email: string; name: string; role: Role; restaurantIds: number[]; pending?: boolean }
export interface Session { accessToken: string; expiresAt: string; user: User }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public fields?: Record<string, string>) {
    super(message);
  }
}

let accessToken: string | null = null;
let refreshing: Promise<Session | null> | null = null;
let onSession: (s: Session | null) => void = () => {};

export function setSessionListener(fn: (s: Session | null) => void) { onSession = fn; }
export function setAccessToken(token: string | null) { accessToken = token; }
export function hasAccessToken() { return accessToken !== null; }

/** One refresh at a time, shared by every request that hit a 401 meanwhile. */
export function refreshSession(): Promise<Session | null> {
  refreshing ??= fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' })
    .then(async (r) => (r.status === 200 ? ((await r.json()) as Session) : null)) // 204: not signed in
    .catch(() => null)
    .then((s) => {
      accessToken = s?.accessToken ?? null;
      onSession(s);
      return s;
    })
    .finally(() => { refreshing = null; });
  return refreshing;
}

interface Options { method?: string; body?: unknown; headers?: Record<string, string>; signal?: AbortSignal }

/**
 * GETs, and writes that are safe to repeat (an Idempotency-Key, or an If-Match version that makes a
 * second attempt fail instead of acting twice), are retried when the server says it is busy (503).
 */
export async function api<T>(path: string, opts: Options = {}, retried = false, busyRetries = 3): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  const res = await fetch(path, {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    credentials: 'same-origin',
    signal: opts.signal,
  });
  if (res.status === 401 && !retried && !path.startsWith('/api/auth/')) {
    const s = await refreshSession();
    if (s) return api<T>(path, opts, true);
  }
  const safeToRepeat = (opts.method ?? 'GET') === 'GET' || 'Idempotency-Key' in headers || 'If-Match' in headers;
  if (res.status === 503 && safeToRepeat && busyRetries > 0) {
    const wait = Number(res.headers.get('Retry-After') ?? '1');
    await new Promise((r) => setTimeout(r, Math.min(5, Math.max(1, wait)) * 1000));
    return api<T>(path, opts, retried, busyRetries - 1);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    throw new ApiError(res.status, data?.code ?? 'error', data?.detail ?? res.statusText, data?.fields);
  }
  return data as T;
}

/** multipart/form-data upload of one file (field "file"), with the same auth and refresh as api(). */
export async function uploadFile<T>(path: string, file: File, retried = false): Promise<T> {
  const form = new FormData();
  form.append('file', file);
  const res = await fetch(path, {
    method: 'POST', body: form, credentials: 'same-origin',
    headers: { Accept: 'application/json', ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
  });
  if (res.status === 401 && !retried) {
    const s = await refreshSession();
    if (s) return uploadFile<T>(path, file, true);
  }
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) throw new ApiError(res.status, data?.code ?? 'error', data?.detail ?? res.statusText, data?.fields);
  return data as T;
}

export interface StreamHandlers {
  onEvent: (name: string, data: string) => void;
  /** resumed: the server is replaying what was missed since the last event (no refetch needed). */
  onOpen?: (resumed: boolean) => void;
  onDown?: () => void;
}

/**
 * Server-sent events over fetch (EventSource cannot send an Authorization
 * header). Reconnects with backoff, sending Last-Event-ID so the server replays
 * exactly what was missed; the server closes the stream when the access token
 * expires, so each reconnect refreshes first if needed.
 */
export function openStream(path: string, h: StreamHandlers): () => void {
  const ctl = new AbortController();
  let attempt = 0;
  let lastId: string | null = null;
  const run = async () => {
    while (!ctl.signal.aborted) {
      try {
        if (!accessToken) await refreshSession();
        const res = await fetch(path, {
          headers: {
            Accept: 'text/event-stream',
            ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
            ...(lastId ? { 'Last-Event-ID': lastId } : {}),
          },
          signal: ctl.signal,
        });
        if (res.status === 401) {
          await refreshSession();
          throw new Error('unauthorized');
        }
        if (!res.ok || !res.body) throw new Error(`stream ${res.status}`);
        attempt = 0;
        h.onOpen?.(lastId !== null);
        const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
        let buf = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += value;
          let idx: number;
          while ((idx = buf.indexOf('\n\n')) >= 0) {
            const block = buf.slice(0, idx);
            buf = buf.slice(idx + 2);
            let name = 'message';
            let id: string | null = null;
            const data: string[] = [];
            for (const line of block.split('\n')) {
              if (line.startsWith('event:')) name = line.slice(6).trim();
              else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
              else if (line.startsWith('id:')) id = line.slice(3).trim();
            }
            if (id) lastId = id;
            if (data.length) h.onEvent(name, data.join('\n'));
          }
        }
      } catch {
        if (ctl.signal.aborted) return;
      }
      h.onDown?.();
      attempt++;
      await new Promise((r) => setTimeout(r, Math.min(10_000, 500 * 2 ** Math.min(attempt, 5))));
    }
  };
  void run();
  return () => ctl.abort();
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
