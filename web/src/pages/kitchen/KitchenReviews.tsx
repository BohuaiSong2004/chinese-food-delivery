import { useState } from 'react';
import { useFetch } from '../../lib/useFetch';
import { useI18n } from '../../lib/i18n';
import { api, ApiError } from '../../lib/api';
import type { RestaurantReviews, Review } from '../../lib/types';
import { ErrorBox, Loading } from '../../components/bits';
import { Stars } from '../../components/Stars';
import { ReviewItem } from '../Reviews';

/** Reviews of this kitchen (hidden ones flagged), with a reply box. */
export function KitchenReviews({ rid }: { rid: number }) {
  const { t } = useI18n();
  const data = useFetch<RestaurantReviews>(`/api/merchant/restaurants/${rid}/reviews?limit=50`);
  if (data.error) return <ErrorBox error={data.error} retry={data.reload} />;
  if (!data.data) return <Loading />;
  return (
    <>
      <p><Stars rating={data.data.rating.average} count={data.data.rating.count} /></p>
      {data.data.page.items.length === 0 ? <p className="muted">{t('reviews.noneYet')}</p> : (
        <ul className="review-list">
          {data.data.page.items.map((r) => <ReviewItem key={r.orderId} r={r}><ReplyBox r={r} onSaved={data.reload} /></ReviewItem>)}
        </ul>
      )}
    </>
  );
}

function ReplyBox({ r, onSaved }: { r: Review; onSaved: () => void }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState(r.reply ?? '');
  const [error, setError] = useState<string | null>(null);
  if (!open) return <button type="button" className="link-button" onClick={() => setOpen(true)}>{r.reply ? t('reviews.editReply') : t('reviews.reply')}</button>;
  return (
    <form method="post" onSubmit={(e) => {
      e.preventDefault();
      setError(null);
      api(`/api/merchant/reviews/${r.orderId}/reply`, { method: 'POST', body: { reply: text } })
        .then(() => { setOpen(false); onSaved(); })
        .catch((err) => setError(err instanceof ApiError ? err.message : String(err)));
    }}>
      <label htmlFor={`reply-${r.orderId}`}>{t('reviews.replyLabel')}</label>
      <textarea id={`reply-${r.orderId}`} required maxLength={1000} rows={2} value={text} onChange={(e) => setText(e.target.value)} />
      {error && <p className="notice error" role="alert">{error}</p>}
      <div className="actions">
        <button type="submit">{t('reviews.sendReply')}</button>
        <button type="button" className="secondary-button" onClick={() => setOpen(false)}>{t('common.cancel')}</button>
      </div>
    </form>
  );
}
