import { useI18n } from '../lib/i18n';

/** Average rating as five stars plus the number, with one accessible name ("4.5 out of 5, 12 reviews"). */
export function Stars({ rating, count, compact = false }: { rating: number; count?: number; compact?: boolean }) {
  const { t } = useI18n();
  if (count === 0) return <span className="muted stars-none">{t('reviews.none')}</span>;
  const label = count === undefined ? t('reviews.stars', { n: rating }) : t('reviews.summary', { n: rating, count });
  const full = Math.round(rating);
  return (
    <span className="stars" role="img" aria-label={label} title={label}>
      <span aria-hidden="true" className="star-row">{'★'.repeat(full)}<span className="star-empty">{'★'.repeat(5 - full)}</span></span>
      {!compact && <span aria-hidden="true" className="star-num"> {rating.toFixed(1)}{count !== undefined ? ` (${count})` : ''}</span>}
    </span>
  );
}
