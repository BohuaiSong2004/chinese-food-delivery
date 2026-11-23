import type { OrderStatus } from '../lib/types';
import { useI18n } from '../lib/i18n';

export function Spice({ level }: { level: number }) {
  const { t } = useI18n();
  if (level <= 0) return null;
  return (
    <span className="spice" role="img" aria-label={t('menu.spice', { n: level })} title={t('menu.spice', { n: level })}>
      {'🌶'.repeat(level)}
    </span>
  );
}

export function Names({ o, as: Tag = 'span' }: { o: { name: string; nameZh: string }; as?: 'span' | 'h2' | 'h3' }) {
  const { names, lang } = useI18n();
  const [primary, secondary] = names(o);
  return (
    <Tag className="names">
      <span lang={lang === 'zh' ? 'zh-CN' : 'en'}>{primary}</span>{' '}
      <span className="secondary" lang={lang === 'zh' ? 'en' : 'zh-CN'}>{secondary}</span>
    </Tag>
  );
}

export function Tags({ tags }: { tags: string[] }) {
  const { label } = useI18n();
  if (!tags.length) return null;
  return (
    <ul className="tags">
      {tags.map((tag) => <li key={tag}>{label(tag)}</li>)}
    </ul>
  );
}

export function StatusBadge({ status }: { status: OrderStatus }) {
  const { t } = useI18n();
  return <span className={`badge status-${status.toLowerCase()}`}>{t(`status.${status}`)}</span>;
}

export function Loading() {
  const { t } = useI18n();
  return <p className="muted" role="status">{t('common.loading')}</p>;
}

export function ErrorBox({ error, retry }: { error: Error; retry?: () => void }) {
  const { t } = useI18n();
  return (
    <div className="notice error" role="alert">
      <p>{t('common.error', { message: error.message })}</p>
      {retry && <button type="button" onClick={retry}>{t('common.retry')}</button>}
    </div>
  );
}
