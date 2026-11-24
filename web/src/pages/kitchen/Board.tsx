import { useState } from 'react';
import { useFetch } from '../../lib/useFetch';
import { useLiveRefresh } from '../../lib/useLiveList';
import { useI18n, type Key } from '../../lib/i18n';
import { api, ApiError } from '../../lib/api';
import { money, shortId, time } from '../../lib/format';
import type { OrderStatus, OrderView } from '../../lib/types';
import { ErrorBox, Loading, Names, StatusBadge } from '../../components/bits';

const COLUMNS: OrderStatus[] = ['PAID', 'ACCEPTED', 'READY', 'PICKED_UP'];

export function Board({ rid }: { rid: number }) {
  const { t, lang } = useI18n();
  const board = useFetch<OrderView[]>(`/api/merchant/restaurants/${rid}/orders`);
  useLiveRefresh(`/api/merchant/restaurants/${rid}/events`, board.reload);
  const [rejecting, setRejecting] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  const act = async (o: OrderView, action: 'accept' | 'reject' | 'ready', body?: unknown) => {
    setError(null);
    try {
      // If-Match: act only on the version shown, so a stale screen cannot double-act.
      await api(`/api/merchant/orders/${o.id}/${action}`, { method: 'POST', headers: { 'If-Match': `"${o.version}"` }, body });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
    setRejecting(null);
    board.reload();
  };

  if (board.error) return <ErrorBox error={board.error} retry={board.reload} />;
  if (!board.data) return <Loading />;
  const done = board.data.filter((o) => o.status === 'DELIVERED' || o.status === 'REJECTED' || o.status === 'CANCELLED');

  return (
    <>
      {error && <p className="notice error" role="alert">{error}</p>}
      <div className="board">
        {COLUMNS.map((col) => {
          const orders = board.data!.filter((o) => o.status === col);
          return (
            <section key={col} className="column" aria-labelledby={`col-${col}`}>
              <h2 id={`col-${col}`}>{t(`merchant.col.${col}` as Key)} <span className="count">{orders.length}</span></h2>
              {orders.length === 0 ? <p className="muted">{t('merchant.empty')}</p> : (
                <ul>
                  {orders.map((o) => (
                    <li key={o.id} className="card ticket" data-order={o.id}>
                      <h3 className="h4">#{shortId(o.id)} · {o.customerName}</h3>
                      <p className="muted">{time(o.createdAt, lang)} · {money(o.totalCents)}</p>
                      <ul className="ticket-items">{o.items.map((l) => <li key={l.dishId}>{l.quantity} × <Names o={l} /></li>)}</ul>
                      {o.deliveryNotes && <p className="notes">“{o.deliveryNotes}”</p>}
                      {col === 'PAID' && rejecting !== o.id && (
                        <div className="actions">
                          <button type="button" onClick={() => void act(o, 'accept')}>{t('merchant.accept')}</button>
                          <button type="button" className="secondary-button" onClick={() => { setRejecting(o.id); setReason(''); }}>{t('merchant.reject')}</button>
                        </div>
                      )}
                      {rejecting === o.id && (
                        <form method="post" onSubmit={(e) => { e.preventDefault(); void act(o, 'reject', { reason }); }}>
                          <label htmlFor={`reason-${o.id}`}>{t('merchant.rejectReason')}</label>
                          <input id={`reason-${o.id}`} required maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />
                          <button type="submit">{t('merchant.confirmReject')}</button>
                        </form>
                      )}
                      {col === 'ACCEPTED' && <button type="button" onClick={() => void act(o, 'ready')}>{t('merchant.ready')}</button>}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          );
        })}
      </div>
      {done.length > 0 && (
        <section aria-labelledby="done">
          <h2 id="done">{t('merchant.col.done')}</h2>
          <ul className="done-list">
            {done.map((o) => <li key={o.id}>#{shortId(o.id)} · {o.customerName} · {money(o.totalCents)} <StatusBadge status={o.status} /></li>)}
          </ul>
        </section>
      )}
    </>
  );
}
