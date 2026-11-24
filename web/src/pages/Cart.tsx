import { Link, useNavigate } from 'react-router';
import { useState } from 'react';
import { useCart } from '../lib/cart';
import { useI18n } from '../lib/i18n';
import { useAuth } from '../lib/auth';
import { useDeliverTo, km } from '../lib/location';
import { useFetch } from '../lib/useFetch';
import { api, ApiError, newIdempotencyKey } from '../lib/api';
import { money } from '../lib/format';
import type { MenuView, OrderView } from '../lib/types';
import { Names } from '../components/bits';
import { AddressForm } from '../components/AddressForm';

export function Cart() {
  const { t, names } = useI18n();
  const { cart, subtotal, setQuantity, clear } = useCart();
  const { user } = useAuth();
  const { deliverTo, addresses, chooseAddress, reloadAddresses } = useDeliverTo();
  const navigate = useNavigate();
  const [notes, setNotes] = useState('');
  const [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // One key per checkout attempt: a double click or a retry after a timeout cannot create two orders.
  const [key, setKey] = useState(newIdempotencyKey);
  const point = deliverTo ? `?lat=${deliverTo.lat.toFixed(4)}&lng=${deliverTo.lng.toFixed(4)}` : '';
  // The fee is only an estimate here; the server prices the order from the saved address.
  const quote = useFetch<MenuView>(cart && cart.lines.length ? `/api/restaurants/${cart.restaurantSlug}${point}` : null);

  if (!cart || cart.lines.length === 0) {
    return (
      <section>
        <h1>{t('cart.title')}</h1>
        <p>{t('cart.empty')}</p>
        <Link to="/">{t('cart.browse')}</Link>
      </section>
    );
  }

  const delivery = quote.data && !quote.loading ? quote.data.restaurant.delivery : undefined;
  const fee = delivery?.feeCents ?? cart.deliveryFeeCents;
  const outOfRange = delivery !== undefined && !delivery.deliverable;
  const total = subtotal + fee;
  const short = cart.minOrderCents - subtotal;
  const [restaurantName] = names({ name: cart.restaurantName, nameZh: cart.restaurantNameZh });
  const changeQty = (dishId: number, q: number) => { setQuantity(dishId, q); setKey(newIdempotencyKey()); };
  const addressId = deliverTo?.addressId;

  const place = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!addressId) return;
    setBusy(true);
    setError(null);
    try {
      // api() resends on 503 with the same Idempotency-Key, so a busy moment cannot create two orders.
      const order = await api<OrderView>('/api/orders', {
        method: 'POST',
        headers: { 'Idempotency-Key': key },
        body: {
          restaurantId: cart.restaurantId,
          items: cart.lines.map((l) => ({ dishId: l.dishId, quantity: l.quantity })),
          addressId,
          deliveryNotes: notes,
        },
      });
      clear();
      if (order.checkoutUrl) window.location.assign(order.checkoutUrl);
      else navigate(`/orders/${order.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err));
      if (err instanceof ApiError && err.status < 500) setKey(newIdempotencyKey());
      setBusy(false);
    }
  };

  return (
    <section className="cart">
      <h1>{t('cart.title')}</h1>
      <p className="muted">{t('cart.from', { name: restaurantName })}</p>
      <ul className="cart-lines">
        {cart.lines.map((l) => {
          const [n] = names(l);
          return (
            <li key={l.dishId} className="cart-line">
              <Names o={l} />
              <div className="qty">
                <button type="button" onClick={() => changeQty(l.dishId, l.quantity - 1)} aria-label={t('cart.decrease', { name: n })}>−</button>
                <output aria-label={t('cart.qty', { name: n })}>{l.quantity}</output>
                <button type="button" onClick={() => changeQty(l.dishId, l.quantity + 1)} aria-label={t('cart.increase', { name: n })}>+</button>
              </div>
              <span className="price">{money(l.priceCents * l.quantity)}</span>
              <button type="button" className="link-button" onClick={() => changeQty(l.dishId, 0)} aria-label={t('cart.remove', { name: n })}>✕</button>
            </li>
          );
        })}
      </ul>
      <dl className="totals">
        <dt>{t('cart.subtotal')}</dt><dd>{money(subtotal)}</dd>
        <dt>{t('cart.delivery')}{delivery && delivery.deliverable ? ` (${km(delivery.distanceM)})` : ''}</dt><dd>{money(fee)}</dd>
        <dt>{t('cart.total')}</dt><dd><strong>{money(total)}</strong></dd>
      </dl>
      {delivery && delivery.deliverable && <p className="muted">{t('cart.eta', { min: delivery.etaMinutes })}</p>}
      {short > 0 && <p className="notice">{t('cart.belowMin', { amount: money(short) })}</p>}

      {!user ? (
        <Link className="button" to="/signin?next=/cart">{t('cart.signInFirst')}</Link>
      ) : (
        <>
          <fieldset className="address-choice">
            <legend>{t('cart.deliverTo')}</legend>
            {addresses === null ? <p className="muted">{t('common.loading')}</p> : addresses.length === 0 && !adding ? (
              <p className="muted">{t('cart.noAddress')}</p>
            ) : addresses.map((a) => (
              <label key={a.id} className="check">
                <input type="radio" name="address" checked={addressId === a.id} onChange={() => { chooseAddress(a); setKey(newIdempotencyKey()); }} />
                <span><strong>{a.label}</strong> — {a.line1}{a.line2 ? `, ${a.line2}` : ''}, {a.city} {a.zip}</span>
              </label>
            ))}
            {!adding && <button type="button" className="link-button" onClick={() => setAdding(true)}>{t('cart.addAddress')}</button>}
          </fieldset>
          {/* a sibling, not a child, of the checkout form: forms cannot nest */}
          {adding && (
            <AddressForm onCancel={() => setAdding(false)} onSaved={(a) => {
              setAdding(false);
              void reloadAddresses();
              chooseAddress(a);
              setKey(newIdempotencyKey());
            }} />
          )}
          <form method="post" onSubmit={place} className="checkout-form">
            {outOfRange && <p className="notice error" role="alert">{t('cart.outOfRange', { km: km(delivery.distanceM) })}</p>}
            <label htmlFor="notes">{t('cart.notes')}</label>
            <textarea id="notes" maxLength={300} rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} />
            {error && <p className="notice error" role="alert">{error}</p>}
            <button type="submit" disabled={busy || short > 0 || !addressId || outOfRange || adding}>{busy ? t('cart.placing') : t('cart.place')}</button>
          </form>
        </>
      )}
    </section>
  );
}

