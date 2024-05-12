import { useCallback, useEffect, useState } from 'react';
import { api } from './api';
import type { Page } from './types';

/**
 * A keyset-paginated list: the first page loads with the path, `more()` appends the next page using
 * the server's opaque cursor. New items arriving meanwhile never shift or duplicate what is shown.
 */
export function usePaged<T>(path: string | null) {
  const [state, setState] = useState<{ path: string | null; items: T[]; next?: string }>({ path: null, items: [] });
  const [error, setError] = useState<Error | null>(null);
  const [busy, setBusy] = useState(false);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!path) return;
    const ctl = new AbortController();
    api<Page<T>>(path, { signal: ctl.signal })
      .then((p) => { setState({ path, items: p.items, next: p.next }); setError(null); })
      .catch((e: Error) => { if (!ctl.signal.aborted) setError(e); });
    return () => ctl.abort();
  }, [path, tick]);

  const more = useCallback(async () => {
    if (!state.next || !path) return;
    setBusy(true);
    try {
      const sep = path.includes('?') ? '&' : '?';
      const p = await api<Page<T>>(`${path}${sep}cursor=${encodeURIComponent(state.next)}`);
      setState((s) => ({ ...s, items: [...s.items, ...p.items], next: p.next }));
    } catch (e) {
      setError(e as Error);
    } finally {
      setBusy(false);
    }
  }, [path, state.next]);

  const reload = useCallback(() => setTick((n) => n + 1), []);
  return { items: state.path === path ? state.items : null, hasMore: !!state.next, more, busy, error, reload };
}
