import { Link } from 'react-router';
import { useI18n } from '../lib/i18n';
import { usePaged } from '../lib/usePaged';
import { dateTime, money, shortId } from '../lib/format';
import type { OrderView } from '../lib/types';
import { ErrorBox, Loading, Names, StatusBadge } from '../components/bits';

export function Orders() {
  const { t, lang } = useI18n();
  const orders = usePaged<OrderView>('/api/orders?limit=15');
  if (orders.error && !orders.items) return <ErrorBox error={orders.error} retry={orders.reload} />;
  if (!orders.items) return <Loading />;
  return (
    <section>
      <h1>{t('orders.title')}</h1>
      {orders.items.length === 0 ? <p>{t('orders.none')}</p> : (
        <ul className="results">
          {orders.items.map((o) => (
            <li key={o.id} className="card">
              <h2 className="h3"><Link to={`/orders/${o.id}`}>{t('order.title', { id: shortId(o.id) })}</Link></h2>
              <p><Names o={o.restaurant} /> · {money(o.totalCents)} · {dateTime(o.createdAt, lang)}</p>
              <StatusBadge status={o.status} />
            </li>
          ))}
        </ul>
      )}
      {orders.hasMore && <button type="button" className="secondary-button load-more" disabled={orders.busy} onClick={() => void orders.more()}>{t('common.more')}</button>}
    </section>
  );
}
