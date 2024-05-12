import { useEffect, useRef } from 'react';
import { openStream } from './api';

/** Refetches a list whenever its stream reports a change (debounced so a burst of events costs one request). */
export function useLiveRefresh(streamPath: string | null, reload: () => void) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (!streamPath) return;
    const stop = openStream(streamPath, {
      // A resumed stream replays what was missed, so only a fresh connection needs a refetch.
      onOpen: (resumed) => { if (!resumed) reload(); },
      onEvent: (name) => {
        if (name === 'resync') { reload(); return; }
        if (name !== 'status') return;
        if (timer.current) clearTimeout(timer.current);
        timer.current = setTimeout(reload, 150);
      },
    });
    return () => {
      stop();
      if (timer.current) clearTimeout(timer.current);
    };
  }, [streamPath, reload]);
}
