import { useCallback, useEffect, useState } from 'react';
import { api } from './api';

/** Loads JSON for the current path; `reload` refetches without clearing what is shown. */
export function useFetch<T>(path: string | null) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!path) return;
    const ctl = new AbortController();
    api<T>(path, { signal: ctl.signal })
      .then((d) => { setData(d); setError(null); })
      .catch((e: Error) => { if (!ctl.signal.aborted) setError(e); });
    return () => ctl.abort();
  }, [path, tick]);

  const reload = useCallback(() => setTick((n) => n + 1), []);
  return { data, error, reload, setData };
}
