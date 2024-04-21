import { useState } from 'react';
import { Link } from 'react-router';
import { useFetch } from '../lib/useFetch';
import { useI18n, type Key } from '../lib/i18n';
import { api } from '../lib/api';
import type { RestaurantStatus } from '../lib/types';
import { ErrorBox, Loading } from '../components/bits';
import { Board } from './kitchen/Board';
import { Earnings } from './kitchen/Earnings';
import { MenuEditor } from './kitchen/MenuEditor';
import { KitchenReviews } from './kitchen/KitchenReviews';
import { KitchenSettings } from './kitchen/KitchenSettings';

interface Summary { id: number; slug: string; name: string; nameZh: string; acceptingOrders: boolean; status: RestaurantStatus; statusNote?: string }
type Tab = 'board' | 'menu' | 'reviews' | 'earnings' | 'settings';

export function Kitchen() {
  const { t, names } = useI18n();
  const mine = useFetch<Summary[]>('/api/merchant/restaurants');
  const [rid, setRid] = useState<number | null>(null);
  const [tab, setTab] = useState<Tab>('board');

  if (mine.error) return <ErrorBox error={mine.error} retry={mine.reload} />;
  if (!mine.data) return <Loading />;
  if (mine.data.length === 0) {
    return (
      <section>
        <h1>{t('merchant.title')}</h1>
        <p>{t('merchant.none')}</p>
        <Link className="button" to="/partner">{t('merchant.applyNow')}</Link>
      </section>
    );
  }
  const current = mine.data.find((r) => r.id === rid) ?? mine.data[0]!;
  const live = current.status === 'LIVE';
  const tabs: Tab[] = live ? ['board', 'menu', 'reviews', 'earnings', 'settings'] : ['menu', 'settings'];
  const shown = tabs.includes(tab) ? tab : tabs[0]!;

  const toggle = async () => {
    await api(`/api/merchant/restaurants/${current.id}/${current.acceptingOrders ? 'pause' : 'resume'}`, { method: 'POST' });
    mine.reload();
  };
  const resubmit = async () => {
    await api(`/api/merchant/restaurants/${current.id}/resubmit`, { method: 'POST' });
    mine.reload();
  };

  return (
    <section className="kitchen">
      <h1>{t('merchant.title')}</h1>
      <div className="toolbar">
        <label htmlFor="restaurant">{t('merchant.restaurant')}</label>
        <select id="restaurant" value={current.id} onChange={(e) => setRid(Number(e.target.value))}>
          {mine.data.map((r) => <option key={r.id} value={r.id}>{names(r)[0]}{r.status !== 'LIVE' ? ` (${t(`rstatus.${r.status}` as Key)})` : ''}</option>)}
        </select>
        {live && (
          <button type="button" className="secondary-button" onClick={() => void toggle()}>
            {current.acceptingOrders ? t('merchant.pause') : t('merchant.resume')}
          </button>
        )}
        {live && <Link to={`/r/${current.slug}`}>{t('merchant.viewStore')}</Link>}
        <Link to="/partner">{t('merchant.addRestaurant')}</Link>
      </div>
      {current.status === 'PENDING' && <p className="notice">{t('merchant.pendingNotice')}</p>}
      {current.status === 'REJECTED' && (
        <div className="notice error">
          <p>{t('merchant.rejectedNotice', { note: current.statusNote ?? '' })}</p>
          <button type="button" onClick={() => void resubmit()}>{t('merchant.resubmit')}</button>
        </div>
      )}
      {current.status === 'SUSPENDED' && <p className="notice error">{t('merchant.suspendedNotice', { note: current.statusNote ?? '' })}</p>}
      {live && !current.acceptingOrders && <p className="notice">{t('merchant.pausedNotice')}</p>}
      <div role="tablist" aria-label={t('merchant.title')} className="tabs">
        {tabs.map((k) => (
          <button key={k} type="button" role="tab" id={`tab-${k}`} aria-selected={shown === k} aria-controls={`panel-${k}`}
            onClick={() => setTab(k)}>{t(`merchant.${k}` as Key)}</button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${shown}`} aria-labelledby={`tab-${shown}`}>
        {/* keyed by restaurant: switching never leaves the previous kitchen's data on screen */}
        {shown === 'board' && <Board key={current.id} rid={current.id} />}
        {shown === 'menu' && <MenuEditor key={current.id} rid={current.id} />}
        {shown === 'reviews' && <KitchenReviews key={current.id} rid={current.id} />}
        {shown === 'earnings' && <Earnings key={current.id} rid={current.id} />}
        {shown === 'settings' && <KitchenSettings key={current.id} rid={current.id} onChanged={mine.reload} />}
      </div>
    </section>
  );
}
