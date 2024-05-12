import { useState } from 'react';
import { useFetch } from '../lib/useFetch';
import { useLiveRefresh } from '../lib/useLiveList';
import { useI18n } from '../lib/i18n';
import { api, ApiError, refreshSession } from '../lib/api';
import { useAuth } from '../lib/auth';
import { dateTime, money, shortId } from '../lib/format';
import { usePaged } from '../lib/usePaged';
import type { OrderView } from '../lib/types';
import { ErrorBox, Loading, Names, StatusBadge } from '../components/bits';

export function Rider() {
  const { user } = useAuth();
  const { t } = useI18n();
  if (user?.pending) {
    return (
      <section className="rider">
        <h1>{t('rider.title')}</h1>
        <p className="notice">{t('rider.pending')}</p>
        <button type="button" className="secondary-button" onClick={() => void refreshSession()}>{t('rider.checkAgain')}</button>
      </section>
    );
  }
  return <RiderConsole />;
}

function RiderConsole() {
  const { t, names } = useI18n();
  const jobs = useFetch<OrderView[]>('/api/rider/jobs');
  const mine = useFetch<OrderView[]>('/api/rider/orders');
  useLiveRefresh('/api/rider/jobs/events', jobs.reload);
  useLiveRefresh('/api/me/events', mine.reload);
  const [error, setError] = useState<string | null>(null);

  const act = async (o: OrderView, action: 'claim' | 'pickup' | 'deliver') => {
    setError(null);
    try {
      await api(`/api/rider/orders/${o.id}/${action}`, { method: 'POST', headers: { 'If-Match': `"${o.version}"` } });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
    jobs.reload();
    mine.reload();
  };

  const active = mine.data?.filter((o) => o.status === 'ACCEPTED' || o.status === 'READY' || o.status === 'PICKED_UP') ?? [];
  return (
    <section className="rider">
      <h1>{t('rider.title')}</h1>
      {error && <p className="notice error" role="alert">{error}</p>}
      <section aria-labelledby="mine">
        <h2 id="mine">{t('rider.mine')}</h2>
        {mine.error ? <ErrorBox error={mine.error} retry={mine.reload} /> : !mine.data ? <Loading /> : active.length === 0 ? <p className="muted">{t('rider.noMine')}</p> : (
          <ul className="results">
            {active.map((o) => (
              <li key={o.id} className="card" data-order={o.id}>
                <h3 className="h4">#{shortId(o.id)} <StatusBadge status={o.status} /></h3>
                <p>{t('rider.from', { name: names(o.restaurant)[0] })} — {o.restaurant.address}</p>
                <p>{t('rider.to', { address: o.deliveryAddress })}</p>
                {o.status === 'ACCEPTED' && <p className="muted">{t('rider.waitingKitchen')}</p>}
                {o.status === 'READY' && <button type="button" onClick={() => void act(o, 'pickup')}>{t('rider.pickup')}</button>}
                {o.status === 'PICKED_UP' && <button type="button" onClick={() => void act(o, 'deliver')}>{t('rider.deliver')}</button>}
              </li>
            ))}
          </ul>
        )}
      </section>
      <RiderHistory />
      <section aria-labelledby="jobs">
        <h2 id="jobs">{t('rider.jobs')}</h2>
        {jobs.error ? <ErrorBox error={jobs.error} retry={jobs.reload} /> : !jobs.data ? <Loading /> : jobs.data.length === 0 ? <p className="muted">{t('rider.noJobs')}</p> : (
          <ul className="results">
            {jobs.data.map((o) => (
              <li key={o.id} className="card" data-order={o.id}>
                <h3 className="h4">#{shortId(o.id)} · <Names o={o.restaurant} /></h3>
                <p>{t('rider.to', { address: o.deliveryAddress })} · {money(o.deliveryFeeCents)}</p>
                <StatusBadge status={o.status} />{' '}
                <button type="button" onClick={() => void act(o, 'claim')}>{t('rider.claim')}</button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </section>
  );
}

function RiderHistory() {
  const { t, lang, names } = useI18n();
  const [open, setOpen] = useState(false);
  const history = usePaged<OrderView>(open ? '/api/rider/orders/history?limit=20' : null);
  return (
    <section aria-labelledby="rider-history">
      <h2 id="rider-history"><button type="button" className="link-button" aria-expanded={open} onClick={() => setOpen(!open)}>{t('rider.history')}</button></h2>
      {open && (history.error ? <ErrorBox error={history.error} retry={history.reload} /> : !history.items ? <Loading /> : (
        <>
          <ul className="done-list">
            {history.items.map((o) => <li key={o.id}>{dateTime(o.createdAt, lang)} · #{shortId(o.id)} · {names(o.restaurant)[0]} · {money(o.deliveryFeeCents)} <StatusBadge status={o.status} /></li>)}
          </ul>
          {history.hasMore && <button type="button" className="secondary-button load-more" disabled={history.busy} onClick={() => void history.more()}>{t('common.more')}</button>}
        </>
      ))}
    </section>
  );
}
