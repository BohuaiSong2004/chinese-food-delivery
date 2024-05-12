import { useEffect, useRef, useState } from 'react';
import { api, openStream } from './api';
import type { OrderView, StatusEvent } from './types';

/**
 * The order, kept current from the SSE stream. Every event and every fetch is
 * compared by version and only ever moves forward, so a late or duplicated
 * event (a replay overlapping the live tail) can never show an older status.
 */
export function useLiveOrder(id: string) {
  const [order, setOrder] = useState<OrderView | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [live, setLive] = useState(false);
  const version = useRef(0);

  useEffect(() => {
    version.current = 0;
    const apply = (o: OrderView) => {
      if (o.version >= version.current) {
        version.current = o.version;
        setOrder(o);
      }
    };
    const refetch = () => api<OrderView>(`/api/orders/${id}`).then(apply).catch(setError);
    const stop = openStream(`/api/orders/${id}/events`, {
      onOpen: () => setLive(true),
      onDown: () => setLive(false),
      onEvent: (name, data) => {
        if (name === 'snapshot') apply(JSON.parse(data) as OrderView);
        if (name === 'resync') void refetch();
        if (name === 'status') {
          const e = JSON.parse(data) as StatusEvent;
          if (e.version > version.current) void refetch();
        }
      },
    });
    void refetch();
    return stop;
  }, [id]);

  return { order, error, live };
}
