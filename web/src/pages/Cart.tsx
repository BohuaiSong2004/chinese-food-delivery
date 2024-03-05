import { Link, useNavigate } from 'react-router';
import { useState } from 'react';
import { useCart } from '../lib/cart';
import { useI18n } from '../lib/i18n';
import { useAuth } from '../lib/auth';
import { api, ApiError, newIdempotencyKey } from '../lib/api';
import { money } from '../lib/format';
import type { OrderView } from '../lib/types';
import { Names } from '../components/bits';

export function Cart() {
  const { t, names } = useI18n();
  const { cart, subtotal, setQuantity, clear } = useCart();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [address, setAddress] = useState('');
  const [notes, setNotes] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // One key per checkout attempt: a double click or a retry after a timeout cannot create two orders.
  const [key, setKey] = useState(newIdempotencyKey);

  if (!cart || cart.lines.length === 0) {
    return (
      <section>
        <h1>{t('cart.title')}</h1>
        <p>{t('cart.empty')}</p>
        <Link to="/">{t('cart.browse')}</Link>
      </section>
    );
  }

  const total = subtotal + cart.deliveryFeeCents;
  const short = cart.minOrderCents - subtotal;
  const [restaurantName] = names({ name: cart.restaurantName, nameZh: cart.restaurantNameZh });
  const changeQty = (dishId: number, q: number) => { setQuantity(dishId, q); setKey(newIdempotencyKey()); };

  const place = async (e: React.FormEvent) => {
    e.preventDefault();
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
          deliveryAddress: address,
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
        <dt>{t('cart.delivery')}</dt><dd>{money(cart.deliveryFeeCents)}</dd>
        <dt>{t('cart.total')}</dt><dd><strong>{money(total)}</strong></dd>
      </dl>
      {short > 0 && <p className="notice">{t('cart.belowMin', { amount: money(short) })}</p>}

      {!user ? (
        <Link className="button" to="/signin?next=/cart">{t('cart.signInFirst')}</Link>
      ) : (
        <form method="post" onSubmit={place} className="checkout-form">
          <label htmlFor="address">{t('cart.address')}</label>
          <input id="address" required maxLength={200} autoComplete="street-address" value={address} onChange={(e) => setAddress(e.target.value)} />
          <label htmlFor="notes">{t('cart.notes')}</label>
          <textarea id="notes" maxLength={300} rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} />
          {error && <p className="notice error" role="alert">{error}</p>}
          <button type="submit" disabled={busy || short > 0}>{busy ? t('cart.placing') : t('cart.place')}</button>
        </form>
      )}
    </section>
  );
}
