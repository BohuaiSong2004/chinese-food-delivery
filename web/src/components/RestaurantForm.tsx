import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { useI18n } from '../lib/i18n';
import type { Place, RestaurantProfile } from '../lib/types';

export const ALL_CUISINES = ['sichuan', 'cantonese', 'northwestern', 'shanghainese', 'hunan', 'shaanxi', 'taiwanese', 'dongbei',
  'yunnan', 'fujian', 'hakka', 'hong-kong', 'beijing'];
const RESTAURANT_TAGS = ['dumplings', 'noodles', 'dim-sum', 'congee', 'seafood', 'bbq', 'grill', 'late-night', 'halal', 'vegetarian'];

export const EMPTY_PROFILE: RestaurantProfile = {
  name: '', nameZh: '', description: '', cuisine: 'sichuan', tags: [], neighborhood: '', address: '', zip: '',
  deliveryRadiusKm: 6, deliveryFeeCents: 299, minOrderCents: 1000, prepMinutes: 20, opensAt: '11:00', closesAt: '21:30',
  open24h: false, timeZone: 'America/Los_Angeles',
};

const dollars = (cents: number) => (cents / 100).toFixed(2);
const cents = (s: string) => Math.round(Number(s) * 100);

/** The restaurant profile, used for the partner application and later in the kitchen's settings. */
export function RestaurantForm({ initial, submitLabel, onSubmit }: {
  initial: RestaurantProfile; submitLabel: string; onSubmit: (p: RestaurantProfile) => Promise<void>;
}) {
  const { t, label } = useI18n();
  const [p, setP] = useState(initial);
  const [fee, setFee] = useState(dollars(initial.deliveryFeeCents));
  const [min, setMin] = useState(dollars(initial.minOrderCents));
  const [zips, setZips] = useState<Place[]>([]);
  const [busy, setBusy] = useState(false);
  useEffect(() => { api<Place[]>('/api/geo/zips').then(setZips).catch(() => undefined); }, []);
  const set = <K extends keyof RestaurantProfile>(k: K, v: RestaurantProfile[K]) => setP((x) => ({ ...x, [k]: v }));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      await onSubmit({ ...p, deliveryFeeCents: cents(fee), minOrderCents: cents(min) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <form method="post" className="restaurant-form" onSubmit={(e) => void submit(e)}>
      <div className="form-grid">
        <div><label htmlFor="rf-name">{t('rf.name')}</label>
          <input id="rf-name" required maxLength={80} value={p.name} onChange={(e) => set('name', e.target.value)} /></div>
        <div><label htmlFor="rf-name-zh">{t('rf.nameZh')}</label>
          <input id="rf-name-zh" required maxLength={40} lang="zh-CN" value={p.nameZh} onChange={(e) => set('nameZh', e.target.value)} /></div>
      </div>
      <label htmlFor="rf-desc">{t('rf.description')}</label>
      <textarea id="rf-desc" maxLength={300} rows={2} value={p.description} onChange={(e) => set('description', e.target.value)} />
      <div className="form-grid">
        <div><label htmlFor="rf-cuisine">{t('rf.cuisine')}</label>
          <select id="rf-cuisine" value={p.cuisine} onChange={(e) => set('cuisine', e.target.value)}>
            {ALL_CUISINES.map((c) => <option key={c} value={c}>{label(c)}</option>)}
          </select></div>
        <div><label htmlFor="rf-hood">{t('rf.neighborhood')}</label>
          <input id="rf-hood" required maxLength={60} value={p.neighborhood} onChange={(e) => set('neighborhood', e.target.value)} /></div>
      </div>
      <fieldset>
        <legend>{t('rf.tags')}</legend>
        <div className="check-grid">
          {RESTAURANT_TAGS.map((tag) => (
            <label key={tag} className="check">
              <input type="checkbox" checked={p.tags.includes(tag)} disabled={!p.tags.includes(tag) && p.tags.length >= 4}
                onChange={(e) => set('tags', e.target.checked ? [...p.tags, tag] : p.tags.filter((x) => x !== tag))} />
              {label(tag)}
            </label>
          ))}
        </div>
      </fieldset>
      <div className="form-grid">
        <div><label htmlFor="rf-address">{t('rf.address')}</label>
          <input id="rf-address" required maxLength={200} autoComplete="street-address" value={p.address} onChange={(e) => set('address', e.target.value)} /></div>
        <div><label htmlFor="rf-zip">{t('addr.zip')}</label>
          <input id="rf-zip" required inputMode="numeric" pattern="\d{5}" maxLength={5} list="rf-zips" value={p.zip} onChange={(e) => set('zip', e.target.value)} />
          <datalist id="rf-zips">{zips.map((z) => <option key={z.zip} value={z.zip}>{z.city}</option>)}</datalist></div>
      </div>
      <div className="form-grid">
        <div><label htmlFor="rf-radius">{t('rf.radius')}</label>
          <input id="rf-radius" type="number" min={1} max={25} step={0.5} required value={p.deliveryRadiusKm} onChange={(e) => set('deliveryRadiusKm', Number(e.target.value))} /></div>
        <div><label htmlFor="rf-prep">{t('rf.prep')}</label>
          <input id="rf-prep" type="number" min={5} max={120} required value={p.prepMinutes} onChange={(e) => set('prepMinutes', Number(e.target.value))} /></div>
        <div><label htmlFor="rf-fee">{t('rf.fee')}</label>
          <input id="rf-fee" inputMode="decimal" pattern="\d+(\.\d{1,2})?" required value={fee} onChange={(e) => setFee(e.target.value)} aria-describedby="rf-fee-hint" />
          <p id="rf-fee-hint" className="muted">{t('rf.feeHint')}</p></div>
        <div><label htmlFor="rf-min">{t('rf.min')}</label>
          <input id="rf-min" inputMode="decimal" pattern="\d+(\.\d{1,2})?" required value={min} onChange={(e) => setMin(e.target.value)} /></div>
      </div>
      <label className="check"><input type="checkbox" checked={p.open24h} onChange={(e) => set('open24h', e.target.checked)} /> {t('rf.open24h')}</label>
      {!p.open24h && (
        <div className="form-grid">
          <div><label htmlFor="rf-opens">{t('rf.opens')}</label>
            <input id="rf-opens" type="time" required value={p.opensAt} onChange={(e) => set('opensAt', e.target.value)} /></div>
          <div><label htmlFor="rf-closes">{t('rf.closes')}</label>
            <input id="rf-closes" type="time" required value={p.closesAt} onChange={(e) => set('closesAt', e.target.value)} aria-describedby="rf-closes-hint" />
            <p id="rf-closes-hint" className="muted">{t('rf.closesHint')}</p></div>
        </div>
      )}
      <button type="submit" disabled={busy}>{submitLabel}</button>
    </form>
  );
}
