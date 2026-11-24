import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, openStream, setAccessToken, setSessionListener } from './api';

type Call = { url: string; init: RequestInit };
let calls: Call[];

function json(status: number, body?: unknown, headers: Record<string, string> = {}) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

function header(c: Call | undefined, name: string) {
  return (c?.init.headers as Record<string, string> | undefined)?.[name];
}

beforeEach(() => {
  calls = [];
  setAccessToken(null);
  setSessionListener(() => {});
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

function serve(handler: (url: string, init: RequestInit) => Response | Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    calls.push({ url, init });
    return handler(url, init);
  }));
}

describe('api()', () => {
  it('sends the access token and parses JSON', async () => {
    setAccessToken('t1');
    serve(() => json(200, { ok: true }));
    await expect(api('/api/x')).resolves.toEqual({ ok: true });
    expect(header(calls[0], 'Authorization')).toBe('Bearer t1');
  });

  it('refreshes once on 401 and repeats the request with the new token', async () => {
    setAccessToken('old');
    const sessions: unknown[] = [];
    setSessionListener((s) => sessions.push(s));
    serve((url, init) => {
      if (url === '/api/auth/refresh') return json(200, { accessToken: 'new', expiresAt: '', user: { id: 1 } });
      return (init.headers as Record<string, string>).Authorization === 'Bearer new' ? json(200, [1]) : json(401, {});
    });
    await expect(api('/api/me/addresses')).resolves.toEqual([1]);
    expect(calls.map((c) => c.url)).toEqual(['/api/me/addresses', '/api/auth/refresh', '/api/me/addresses']);
    expect(sessions).toHaveLength(1);
  });

  it('shares one refresh between requests that all got 401', async () => {
    setAccessToken('old');
    serve((url, init) => {
      if (url === '/api/auth/refresh') return json(200, { accessToken: 'new', expiresAt: '', user: { id: 1 } });
      return (init.headers as Record<string, string>).Authorization === 'Bearer new' ? json(200, {}) : json(401, {});
    });
    await Promise.all([api('/api/a'), api('/api/b'), api('/api/c')]);
    expect(calls.filter((c) => c.url === '/api/auth/refresh')).toHaveLength(1);
  });

  it('does not try to refresh for the auth endpoints themselves', async () => {
    serve(() => json(401, { code: 'bad_credentials', detail: 'Wrong email or password' }));
    await expect(api('/api/auth/signin', { method: 'POST', body: {} })).rejects.toMatchObject({ status: 401, code: 'bad_credentials' });
    expect(calls).toHaveLength(1);
  });

  it('retries a busy server (503) for reads and writes that are safe to repeat, honouring Retry-After', async () => {
    vi.useFakeTimers();
    let n = 0;
    serve(() => (++n < 3 ? json(503, { code: 'busy' }, { 'Retry-After': '2' }) : json(201, { id: 'o1' })));
    const p = api('/api/orders', { method: 'POST', body: {}, headers: { 'Idempotency-Key': 'k1' } });
    await vi.advanceTimersByTimeAsync(2000);
    await vi.advanceTimersByTimeAsync(2000);
    await expect(p).resolves.toEqual({ id: 'o1' });
    expect(calls.every((c) => header(c, 'Idempotency-Key') === 'k1')).toBe(true);
  });

  it('does not repeat a write that could act twice', async () => {
    serve(() => json(503, { code: 'busy', detail: 'busy' }));
    await expect(api('/api/orders', { method: 'POST', body: {} })).rejects.toBeInstanceOf(ApiError);
    expect(calls).toHaveLength(1);
  });

  it('turns problem details into an ApiError with field messages', async () => {
    serve(() => json(400, { code: 'validation', detail: 'Check the form', fields: { email: 'must be an email' } }));
    const err = await api('/api/auth/signup', { method: 'POST', body: {} }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 400, code: 'validation', message: 'Check the form', fields: { email: 'must be an email' } });
  });

  it('returns undefined for 204', async () => {
    serve(() => new Response(null, { status: 204 }));
    await expect(api('/api/auth/reset', { method: 'POST', body: {} })).resolves.toBeUndefined();
  });
});

describe('openStream()', () => {
  function sse(chunks: string[]) {
    const enc = new TextEncoder();
    return new Response(new ReadableStream({
      start(c) {
        chunks.forEach((x) => c.enqueue(enc.encode(x)));
        c.close();
      },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  }

  it('parses events split across chunks and resumes from the last event id after a drop', async () => {
    vi.useFakeTimers();
    setAccessToken('t');
    const events: [string, string][] = [];
    const opens: boolean[] = [];
    let connection = 0;
    serve(() => (++connection === 1
      ? sse([': connected\n\n', 'id: 1-0\nevent: status\nda', 'ta: {"v":1}\n\n', 'id: 2-0\nevent: status\ndata: line one\ndata: line two\n\n'])
      : sse(['id: 3-0\nevent: rider\ndata: {"etaMin":4}\n\n'])));
    const stop = openStream('/api/orders/o1/events', { onEvent: (n, d) => events.push([n, d]), onOpen: (r) => opens.push(r) });
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1100); // backoff before reconnecting
    stop();
    expect(events).toEqual([['status', '{"v":1}'], ['status', 'line one\nline two'], ['rider', '{"etaMin":4}']]);
    expect(opens).toEqual([false, true]);
    expect(header(calls[0], 'Last-Event-ID')).toBeUndefined();
    expect(header(calls[1], 'Last-Event-ID')).toBe('2-0');
    expect(header(calls[1], 'Authorization')).toBe('Bearer t');
  });

  it('stops reconnecting once closed', async () => {
    vi.useFakeTimers();
    setAccessToken('t');
    serve(() => sse([]));
    const stop = openStream('/api/me/events', { onEvent: () => {} });
    await vi.advanceTimersByTimeAsync(0);
    stop();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(calls).toHaveLength(1);
  });
});
