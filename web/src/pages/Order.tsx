import { Link, useParams, useSearchParams } from 'react-router';
import { useState } from 'react';
import { useI18n, type Key } from '../lib/i18n';
import { useLiveOrder } from '../lib/useLiveOrder';
import { api, ApiError } from '../lib/api';
import { dateTime, money, shortId, time } from '../lib/format';
import { ErrorBox, Loading, Names, StatusBadge } from '../components/bits';
import { Stars } from '../components/Stars';
import { km } from '../lib/location';
import type { OrderStatus, OrderView, RiderTracking } from '../lib/types';

const STEPS: [OrderStatus, Key][] = [
  ['PENDING_PAYMENT', 'step.placed'], ['PAID', 'step.paid'], ['ACCEPTED', 'step.accepted'],
  ['READY', 'step.ready'], ['PICKED_UP', 'step.picked'], ['DELIVERED', 'step.delivered'],
];

export function Order() {
  const { id = '' } = useParams();
  const [params] = useSearchParams();
  const { t, lang } = useI18n();
  const { order, error, live, rider } = useLiveOrder(id);
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

      {rider && order.riderId && (order.status === 'ACCEPTED' || order.status === 'READY' || order.status === 'PICKED_UP') && (
        <RiderProgress rider={rider} total={order.distanceM} name={order.riderName} />
      )}

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
        <p className="muted">{t('order.deliverTo')}: {order.deliveryAddress}{order.distanceM ? ` (${km(order.distanceM)})` : ''} · {dateTime(order.createdAt, lang)}</p>
        {order.riderName && <p className="muted">{t('order.rider', { name: order.riderName })}</p>}
      </section>

      {order.status === 'DELIVERED' && <ReviewBox order={order} />}

      {(order.status === 'PENDING_PAYMENT' || order.status === 'PAID') && (
        <button type="button" className="secondary-button" onClick={() => void cancel()}>{t('order.cancel')}</button>
      )}
      {actionError && <p className="notice error" role="alert">{actionError}</p>}
    </article>
  );
}

/** After delivery: leave one review (stars + optional comment), then see it and any reply. */
function ReviewBox({ order }: { order: OrderView }) {
  const { t } = useI18n();
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<OrderView['review'] | null>(order.review ?? null);
  if (done) {
    return (
      <section aria-labelledby="your-review" className="card">
        <h2 id="your-review">{t('review.yours')}</h2>
        <p><Stars rating={done.rating} /></p>
        {done.comment && <p>{done.comment}</p>}
        {done.reply && <p className="reply"><strong>{t('reviews.fromKitchen')}</strong> {done.reply}</p>}
      </section>
    );
  }
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api(`/api/orders/${order.id}/review`, { method: 'POST', body: { rating, comment } });
      setDone({ rating, comment: comment.trim() });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err));
    }
  };
  return (
    <section aria-labelledby="rate" className="card">
      <h2 id="rate">{t('review.title')}</h2>
      <form method="post" onSubmit={(e) => void submit(e)}>
        <fieldset className="star-input">
          <legend>{t('review.stars')}</legend>
          {[1, 2, 3, 4, 5].map((n) => (
            <label key={n} className={n <= rating ? 'on' : ''}>
              <input type="radio" name="rating" value={n} required checked={rating === n} onChange={() => setRating(n)} />
              <span aria-hidden="true">★</span><span className="visually-hidden">{t('reviews.stars', { n })}</span>
            </label>
          ))}
        </fieldset>
        <label htmlFor="review-comment">{t('review.comment')}</label>
        <textarea id="review-comment" maxLength={1000} rows={3} value={comment} onChange={(e) => setComment(e.target.value)} />
        {error && <p className="notice error" role="alert">{error}</p>}
        <button type="submit" disabled={rating === 0}>{t('review.submit')}</button>
      </form>
    </section>
  );
}

/** Where the rider is: a line from the kitchen to you, with the rider on it, and plain text for everyone. */
function RiderProgress({ rider, total, name }: { rider: RiderTracking; total?: number; name?: string }) {
  const { t } = useI18n();
  const toYou = rider.heading === 'customer';
  const fraction = toYou && total ? Math.min(1, Math.max(0, 1 - rider.distanceM / Math.max(total, rider.distanceM))) : 0;
  const x = 20 + fraction * 260;
  return (
    <section aria-labelledby="rider-title" className="card rider-progress">
      <h2 id="rider-title" className="h3">{toYou ? t('track.onTheWay', { name: name ?? '' }) : t('track.toKitchen', { name: name ?? '' })}</h2>
      <p aria-live="polite">{t('track.distance', { km: km(rider.distanceM), min: rider.etaMinutes })}</p>
      <svg viewBox="0 0 300 40" width="100%" height="40" aria-hidden="true" className="track">
        <line x1="20" y1="20" x2="280" y2="20" className="track-line" />
        <line x1="20" y1="20" x2={x} y2="20" className="track-done" />
        <circle cx="20" cy="20" r="7" className="track-kitchen" />
        <circle cx="280" cy="20" r="7" className="track-home" />
        <text x={x} y="27" textAnchor="middle" fontSize="20">🛵</text>
      </svg>
    </section>
  );
}
