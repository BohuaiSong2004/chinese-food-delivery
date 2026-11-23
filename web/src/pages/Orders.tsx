import { Link } from 'react-router';
import { useFetch } from '../lib/useFetch';
import { useI18n } from '../lib/i18n';
import { dateTime, money, shortId } from '../lib/format';
import type { OrderView } from '../lib/types';
import { ErrorBox, Loading, Names, StatusBadge } from '../components/bits';

export function Orders() {
  const { t, lang } = useI18n();
  const orders = useFetch<OrderView[]>('/api/orders');
  if (orders.error) return <ErrorBox error={orders.error} retry={orders.reload} />;
  if (!orders.data) return <Loading />;
  return (
    <section>
      <h1>{t('orders.title')}</h1>
      {orders.data.length === 0 ? <p>{t('orders.none')}</p> : (
        <ul className="results">
          {orders.data.map((o) => (
            <li key={o.id} className="card">
              <h2 className="h3"><Link to={`/orders/${o.id}`}>{t('order.title', { id: shortId(o.id) })}</Link></h2>
              <p><Names o={o.restaurant} /> · {money(o.totalCents)} · {dateTime(o.createdAt, lang)}</p>
              <StatusBadge status={o.status} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
