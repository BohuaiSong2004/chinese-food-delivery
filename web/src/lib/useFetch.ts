import { useCallback, useEffect, useState } from 'react';
import { api } from './api';

/**
 * Loads JSON for the current path; `reload` refetches without clearing what is shown.
 * `loading` is true while the data on screen belongs to a different path (e.g. the previous
 * search filters), so callers can mark it stale instead of presenting it as the answer.
 */
export function useFetch<T>(path: string | null) {
  const [state, setState] = useState<{ path: string | null; data: T | null }>({ path: null, data: null });
  const [error, setError] = useState<Error | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!path) return;
    const ctl = new AbortController();
    api<T>(path, { signal: ctl.signal })
      .then((d) => { setState({ path, data: d }); setError(null); })
      .catch((e: Error) => { if (!ctl.signal.aborted) setError(e); });
    return () => ctl.abort();
  }, [path, tick]);

  const reload = useCallback(() => setTick((n) => n + 1), []);
  const setData = useCallback((d: T) => setState((s) => ({ ...s, data: d })), []);
  return { data: state.data, loading: state.path !== path, error, reload, setData };
}
