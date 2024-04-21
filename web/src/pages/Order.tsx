import { Link, useParams, useSearchParams } from 'react-router';
import { useState } from 'react';
import { useI18n, type Key } from '../lib/i18n';
import { useLiveOrder } from '../lib/useLiveOrder';
import { api, ApiError } from '../lib/api';
import { dateTime, money, shortId, time } from '../lib/format';
import type { OrderStatus } from '../lib/types';
import { ErrorBox, Loading, Names, StatusBadge } from '../components/bits';

const STEPS: [OrderStatus, Key][] = [
  ['PENDING_PAYMENT', 'step.placed'], ['PAID', 'step.paid'], ['ACCEPTED', 'step.accepted'],
  ['READY', 'step.ready'], ['PICKED_UP', 'step.picked'], ['DELIVERED', 'step.delivered'],
];

export function Order() {
  const { id = '' } = useParams();
  const [params] = useSearchParams();
  const { t, lang } = useI18n();
  const { order, error, live } = useLiveOrder(id);
  const [actionError, setActionError] = useState<string | null>(null);

  if (error && !order) return <ErrorBox error={error} />;
  if (!order) return <Loading />;

  const reached = new Map(order.history.map((h) => [h.status, h.at]));
  const cancel = async () => {
    setActionError(null);
    try {
      await api(`/api/orders/${id}/cancel`, { method: 'POST', headers: { 'If-Match': `"${order.version}"` } });
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : String(e));
    }
  };
  const ended = order.status === 'CANCELLED' || order.status === 'REJECTED';

  return (
    <article className="order">
      <header className="order-head">
        <h1>{t('order.title', { id: shortId(order.id) })}</h1>
        <span className={live ? 'live on' : 'live'}>{live ? t('order.live') : t('order.reconnecting')}</span>
      </header>
      <p><Link to={`/r/${order.restaurant.slug}`}><Names o={order.restaurant} /></Link></p>
      <p aria-live="polite" className="order-status">
        <span className="visually-hidden">{t('order.updated', { status: t(`status.${order.status}`) })}</span>
        <StatusBadge status={order.status} />
      </p>

      {order.status === 'PENDING_PAYMENT' && (
        <div className="notice">
          {params.get('payment') === 'succeeded' ? <p>{t('order.waiting')}</p> : (
            <>
              <p>{t('order.payBy', { time: time(order.paymentDeadline, lang) })}</p>
              {order.checkoutUrl && <a className="button" href={order.checkoutUrl}>{t('order.payNow')}</a>}
            </>
          )}
        </div>
      )}
      {order.status === 'CANCELLED' && <p className="notice">{t('order.cancelled', { reason: order.cancelReason ?? '' })}</p>}
      {order.status === 'REJECTED' && <p className="notice">{t('order.rejected', { reason: order.cancelReason ?? '' })}</p>}
      {ended && reached.has('PAID') && <p className="muted">{t('order.refunded')}</p>}

      {!ended && (
        <section aria-labelledby="timeline">
          <h2 id="timeline">{t('order.timeline')}</h2>
          <ol className="timeline">
            {STEPS.map(([s, label]) => (
              <li key={s} className={reached.has(s) ? 'done' : ''} aria-current={order.status === s ? 'step' : undefined}>
                <span>{t(label)}</span>
                {reached.get(s) && <time dateTime={reached.get(s)}>{time(reached.get(s)!, lang)}</time>}
              </li>
            ))}
          </ol>
        </section>
      )}

      <section aria-labelledby="items">
        <h2 id="items">{t('order.items')}</h2>
        <ul className="cart-lines">
          {order.items.map((l) => (
            <li key={l.dishId} className="cart-line"><Names o={l} /><span>× {l.quantity}</span><span className="price">{money(l.unitPriceCents * l.quantity)}</span></li>
          ))}
        </ul>
        <dl className="totals">
          <dt>{t('cart.subtotal')}</dt><dd>{money(order.subtotalCents)}</dd>
          <dt>{t('cart.delivery')}</dt><dd>{money(order.deliveryFeeCents)}</dd>
          <dt>{t('cart.total')}</dt><dd><strong>{money(order.totalCents)}</strong></dd>
        </dl>
        <p className="muted">{t('order.deliverTo')}: {order.deliveryAddress} · {dateTime(order.createdAt, lang)}</p>
      </section>

      {(order.status === 'PENDING_PAYMENT' || order.status === 'PAID') && (
        <button type="button" className="secondary-button" onClick={() => void cancel()}>{t('order.cancel')}</button>
      )}
      {actionError && <p className="notice error" role="alert">{actionError}</p>}
    </article>
  );
}
