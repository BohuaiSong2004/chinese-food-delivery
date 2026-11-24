import { Link, useNavigate } from 'react-router';
import { useState } from 'react';
import { useFetch } from '../lib/useFetch';
import { useI18n } from '../lib/i18n';
import { money, time } from '../lib/format';
import { km, useDeliverTo } from '../lib/location';
import type { RestaurantView, TrendingDish } from '../lib/types';
import { ErrorBox, Loading, Names } from '../components/bits';
import { Photo } from '../components/DishImage';
import { Stars } from '../components/Stars';

export const CUISINES = ['sichuan', 'cantonese', 'northwestern', 'shanghainese', 'hunan', 'shaanxi', 'taiwanese', 'dongbei'];

export function DeliveryLine({ r }: { r: RestaurantView }) {
  const { t } = useI18n();
  const d = r.delivery;
  if (!d) return <p className="muted">{t('r.fee', { fee: money(r.deliveryFeeCents) })} · {t('r.prep', { min: r.prepMinutes })}</p>;
  if (!d.deliverable) return <p className="muted out-of-range">{t('r.outOfRange', { km: km(d.distanceM) })}</p>;
  return <p className="muted">{t('r.fee', { fee: money(d.feeCents) })} · {t('r.eta', { min: d.etaMinutes })} · {km(d.distanceM)}</p>;
}

export function RestaurantCard({ r, eager = false }: { r: RestaurantView; eager?: boolean }) {
  const { t, lang, label } = useI18n();
  const state = !r.acceptingOrders ? t('r.paused') : r.openNow ? t('r.open') : r.nextOpening
    ? t('r.opensAt', { time: time(r.nextOpening, lang) }) : t('r.closed');
  return (
    <li className={`card restaurant-card${r.delivery && !r.delivery.deliverable ? ' dim' : ''}`}>
      <Photo base={r.image} sizes="(max-width: 48rem) 100vw, 22rem" className="card-photo" eager={eager} />
      <div className="card-body">
        <h3><Link to={`/r/${r.slug}`}><Names o={r} /></Link></h3>
        <p className="muted">{label(r.cuisine)} · {r.neighborhood} · <Stars rating={r.rating} count={r.ratingCount} /></p>
        <p className={r.openNow && r.acceptingOrders ? 'open' : 'closed'}>
          {state} · {r.hours === '24h' ? t('r.hours24') : t('r.hours', { hours: r.hours })}
        </p>
        <DeliveryLine r={r} />
      </div>
    </li>
  );
}

export function Home() {
  const { t, label } = useI18n();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const { query, deliverTo } = useDeliverTo();
  const restaurants = useFetch<RestaurantView[]>(`/api/restaurants${query ? `?${query}` : ''}`);
  const trending = useFetch<TrendingDish[]>('/api/trending?limit=6');

  return (
    <>
      <section className="hero">
        <h1>{t('home.title')}</h1>
        <p>{t('home.subtitle')}</p>
        <form role="search" method="get" action="/search" className="search-form"
          onSubmit={(e) => { e.preventDefault(); navigate(`/search?q=${encodeURIComponent(q)}`); }}>
          <label htmlFor="home-q" className="visually-hidden">{t('home.searchLabel')}</label>
          <input id="home-q" name="q" type="search" value={q} onChange={(e) => setQ(e.target.value)}
            placeholder={t('home.searchPlaceholder')} autoComplete="off" />
          <button type="submit">{t('home.searchButton')}</button>
        </form>
      </section>

      <section aria-labelledby="cuisines">
        <h2 id="cuisines">{t('home.cuisines')}</h2>
        <ul className="chips">
          {CUISINES.map((c) => <li key={c}><Link className="chip" to={`/search?cuisine=${c}`}>{label(c)}</Link></li>)}
        </ul>
      </section>

      <section aria-labelledby="trending">
        <h2 id="trending">{t('home.trending')}</h2>
        {trending.error ? <ErrorBox error={trending.error} retry={trending.reload} /> : !trending.data ? <Loading /> :
          trending.data.length === 0 ? <p className="muted">{t('home.trendingEmpty')}</p> : (
            <ol className="trending">
              {trending.data.map((d) => (
                <li key={d.dishId} className="card">
                  <Link to={`/r/${d.restaurantSlug}`}><Names o={d} /></Link>
                  <p className="muted">{d.restaurantName} · {money(d.priceCents)}</p>
                  <p className="hot">{t('home.orders', { n: d.orders })}</p>
                </li>
              ))}
            </ol>
          )}
      </section>

      <section aria-labelledby="restaurants">
        <h2 id="restaurants">{deliverTo ? t('home.restaurantsNear', { place: deliverTo.label }) : t('home.restaurants')}</h2>
        {restaurants.error ? <ErrorBox error={restaurants.error} retry={restaurants.reload} /> : !restaurants.data ? <Loading /> : (
          <ul className={`grid${restaurants.loading ? ' stale' : ''}`} aria-busy={restaurants.loading}>
            {restaurants.data.map((r, i) => <RestaurantCard key={r.id} r={r} eager={i < 2} />)}
          </ul>
        )}
      </section>
    </>
  );
}
