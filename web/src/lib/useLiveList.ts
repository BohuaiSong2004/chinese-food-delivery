import { useEffect, useRef } from 'react';
import { openStream } from './api';

/** Refetches a list whenever its stream reports a change (debounced so a burst of events costs one request). */
export function useLiveRefresh(streamPath: string | null, reload: () => void) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (!streamPath) return;
    const stop = openStream(streamPath, {
      onOpen: reload, // catch up on anything missed while disconnected
      onEvent: (name) => {
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
