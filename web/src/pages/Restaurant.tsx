import { Link, useParams } from 'react-router';
import { useEffect, useState } from 'react';
import { useFetch } from '../lib/useFetch';
import { useI18n } from '../lib/i18n';
import { useCart, type RestaurantRef } from '../lib/cart';
import { useAuth } from '../lib/auth';
import { money, time } from '../lib/format';
import type { DishView, MenuView } from '../lib/types';
import { ErrorBox, Loading, Names, Spice, Tags } from '../components/bits';

export function Restaurant() {
  const { slug } = useParams();
  const { t, lang, label, names } = useI18n();
  const { user } = useAuth();
  const cart = useCart();
  const menu = useFetch<MenuView>(`/api/restaurants/${slug}`);
  const [conflict, setConflict] = useState<DishView | null>(null);

  useEffect(() => {
    const id = window.location.hash.slice(1);
    if (menu.data && id) document.getElementById(id)?.scrollIntoView();
  }, [menu.data]);

  if (menu.error) return <ErrorBox error={menu.error} retry={menu.reload} />;
  if (!menu.data) return <Loading />;
  const r = menu.data.restaurant;
  const ref: RestaurantRef = { id: r.id, slug: r.slug, name: r.name, nameZh: r.nameZh, deliveryFeeCents: r.deliveryFeeCents, minOrderCents: r.minOrderCents };
  const canOrder = r.openNow && r.acceptingOrders && (!user || user.role === 'CUSTOMER');
  const line = (d: DishView) => ({ dishId: d.id, name: d.name, nameZh: d.nameZh, priceCents: d.priceCents });
  const add = (d: DishView) => {
    if (!cart.add(ref, line(d))) setConflict(d);
  };
  const inCart = cart.cart?.restaurantId === r.id;
  const [cartName] = cart.cart ? names({ name: cart.cart.restaurantName, nameZh: cart.cart.restaurantNameZh }) : [''];

  return (
    <article className="restaurant">
      <header className="restaurant-head">
        <h1><Names o={r} /></h1>
        <p>{r.description}</p>
        <p className="muted">
          {label(r.cuisine)} · {r.address} · {r.hours === '24h' ? t('r.hours24') : t('r.hours', { hours: r.hours })}
        </p>
        <p className="muted">
          {t('r.fee', { fee: money(r.deliveryFeeCents) })} · {t('r.prep', { min: r.prepMinutes })}
          {r.minOrderCents > 0 && <> · {t('r.min', { amount: money(r.minOrderCents) })}</>}
        </p>
        {!r.acceptingOrders && <p className="notice">{t('r.paused')}</p>}
        {r.acceptingOrders && !r.openNow && r.nextOpening && (
          <p className="notice">{t('menu.closedNotice', { time: time(r.nextOpening, lang) })}</p>
        )}
      </header>

      {conflict && (
        <div className="notice" role="alertdialog" aria-labelledby="cart-conflict">
          <p id="cart-conflict">{t('menu.newCart', { name: cartName })}</p>
          <button type="button" onClick={() => { cart.replaceWith(ref, line(conflict)); setConflict(null); }}>{t('menu.startNew')}</button>{' '}
          <button type="button" className="secondary-button" onClick={() => setConflict(null)}>{t('menu.keep')}</button>
        </div>
      )}

      {menu.data.popular.length > 0 && (
        <section aria-labelledby="popular" className="popular">
          <h2 id="popular">{t('menu.popular')}</h2>
          <ol>
            {menu.data.popular.map((p) => (
              <li key={p.dishId}><a href={`#dish-${p.dishId}`}><Names o={p} /></a> <span className="hot">{t('menu.ordered', { n: p.orders })}</span></li>
            ))}
          </ol>
        </section>
      )}

      {menu.data.categories.map((c) => (
        <section key={c.id} aria-labelledby={`cat-${c.id}`}>
          <h2 id={`cat-${c.id}`}><Names o={c} /></h2>
          <ul className="dishes">
            {c.dishes.map((d) => {
              const soldOut = d.limited && (d.stockRemaining ?? 0) <= 0;
              const [dishName] = names(d);
              return (
                <li key={d.id} id={`dish-${d.id}`} className="card dish">
                  <div>
                    <h3><Names o={d} /> <Spice level={d.spiceLevel} /></h3>
                    <p>{d.description}</p>
                    <Tags tags={d.tags} />
                    {d.limited && !soldOut && <p className="limited">{t('menu.left', { n: d.stockRemaining ?? 0 })}</p>}
                  </div>
                  <div className="dish-buy">
                    <span className="price">{money(d.priceCents)}</span>
                    {!d.available ? <span className="muted">{t('menu.unavailable')}</span>
                      : soldOut ? <span className="muted">{t('menu.soldOut')}</span>
                      : canOrder && <button type="button" onClick={() => add(d)} aria-label={t('menu.addLabel', { name: dishName })}>{t('menu.add')}</button>}
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      ))}

      {inCart && cart.count > 0 && (
        <Link to="/cart" className="cart-bar">{t('menu.viewCart', { n: cart.count, total: money(cart.subtotal) })}</Link>
      )}
    </article>
  );
}
