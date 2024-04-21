import { useCallback, useState } from 'react';
import { api } from '../lib/api';
import { useFetch } from '../lib/useFetch';
import { useI18n } from '../lib/i18n';
import { dateTime } from '../lib/format';
import type { Review, RestaurantReviews } from '../lib/types';
import { ErrorBox, Loading } from '../components/bits';
import { Stars } from '../components/Stars';

export function ReviewItem({ r, children }: { r: Review; children?: React.ReactNode }) {
  const { t, lang } = useI18n();
  return (
    <li className={`review${r.hidden ? ' hidden-review' : ''}`}>
      <p><Stars rating={r.rating} compact /> <strong>{r.author}</strong> <span className="muted">· {dateTime(r.createdAt, lang)}</span>
        {r.hidden && <span className="badge status-cancelled"> {t('reviews.hidden')}</span>}</p>
      {r.comment && <p>{r.comment}</p>}
      {r.reply && <p className="reply"><strong>{t('reviews.fromKitchen')}</strong> {r.reply}</p>}
      {children}
    </li>
  );
}

/** Public reviews, newest first, loaded a page at a time with a keyset cursor. */
export function Reviews({ slug }: { slug: string }) {
  const { t } = useI18n();
  const first = useFetch<RestaurantReviews>(`/api/restaurants/${slug}/reviews?limit=5`);
  const [more, setMore] = useState<{ items: Review[]; next?: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const next = more ? more.next : first.data?.page.next;
  const loadMore = useCallback(async () => {
    if (!next) return;
    setBusy(true);
    const page = await api<RestaurantReviews>(`/api/restaurants/${slug}/reviews?limit=10&cursor=${encodeURIComponent(next)}`);
    setMore((m) => ({ items: [...(m?.items ?? []), ...page.page.items], next: page.page.next }));
    setBusy(false);
  }, [next, slug]);

  return (
    <section id="reviews" aria-labelledby="reviews-title" className="reviews">
      <h2 id="reviews-title">{t('reviews.title')}</h2>
      {first.error ? <ErrorBox error={first.error} retry={first.reload} /> : !first.data ? <Loading /> : (
        <>
          <p><Stars rating={first.data.rating.average} count={first.data.rating.count} /></p>
          {first.data.rating.count === 0 ? <p className="muted">{t('reviews.beFirst')}</p> : (
            <ul className="review-list">
              {[...first.data.page.items, ...(more?.items ?? [])].map((r) => <ReviewItem key={r.orderId} r={r} />)}
            </ul>
          )}
          {next && <button type="button" className="secondary-button" disabled={busy} onClick={() => void loadMore()}>{t('reviews.more')}</button>}
        </>
      )}
    </section>
  );
}
