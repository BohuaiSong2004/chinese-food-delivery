import { useState } from 'react';
import { Link } from 'react-router';
import { useFetch } from '../lib/useFetch';
import { useI18n, type Key } from '../lib/i18n';
import { api, ApiError } from '../lib/api';
import { dateTime } from '../lib/format';
import type { RestaurantReviews, RestaurantStatus, SearchMiss } from '../lib/types';
import { ErrorBox, Loading, Names } from '../components/bits';
import { Stars } from '../components/Stars';
import { ReviewItem } from './Reviews';

interface PendingRestaurant { id: number; slug: string; name: string; nameZh: string; cuisine: string; neighborhood: string; address: string; submittedAt: string; ownerName?: string; ownerEmail?: string }
interface PendingRider { id: number; name: string; email: string; signedUpAt: string }
interface Queue { restaurants: PendingRestaurant[]; riders: PendingRider[] }
interface Summary { id: number; slug: string; name: string; nameZh: string; status: RestaurantStatus; statusNote?: string }
interface AuditEntry { id: number; actor: string; action: string; subject: string; note?: string; at: string }
type Tab = 'queue' | 'restaurants' | 'ops';

const message = (err: unknown) => (err instanceof ApiError ? err.message : String(err));

/** Operations console: onboarding review, restaurant moderation, ledger and platform health. */
export function Admin() {
  const { t } = useI18n();
  const [tab, setTab] = useState<Tab>('queue');
  return (
    <section className="admin">
      <h1>{t('admin.title')}</h1>
      <div role="tablist" aria-label={t('admin.title')} className="tabs">
        {(['queue', 'restaurants', 'ops'] as const).map((k) => (
          <button key={k} type="button" role="tab" id={`atab-${k}`} aria-selected={tab === k} aria-controls={`apanel-${k}`}
            onClick={() => setTab(k)}>{t(`admin.${k}` as Key)}</button>
        ))}
      </div>
      <div role="tabpanel" id={`apanel-${tab}`} aria-labelledby={`atab-${tab}`}>
        {tab === 'queue' && <ReviewQueue />}
        {tab === 'restaurants' && <Restaurants />}
        {tab === 'ops' && <Operations />}
      </div>
    </section>
  );
}

/** Approve or reject with a note; each decision is one compare-and-set on the server. */
function Decision({ approve, reject, label }: { approve: () => Promise<unknown>; reject: (note: string) => Promise<unknown>; label: string }) {
  const { t } = useI18n();
  const [rejecting, setRejecting] = useState(false);
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const run = (p: Promise<unknown>) => p.catch((e) => setError(message(e)));
  return (
    <>
      {error && <p className="notice error" role="alert">{error}</p>}
      {!rejecting ? (
        <div className="actions">
          <button type="button" onClick={() => void run(approve())} aria-label={t('admin.approveLabel', { name: label })}>{t('admin.approve')}</button>
          <button type="button" className="secondary-button" onClick={() => setRejecting(true)} aria-label={t('admin.rejectLabel', { name: label })}>{t('admin.reject')}</button>
        </div>
      ) : (
        <form method="post" onSubmit={(e) => { e.preventDefault(); void run(reject(note)); }}>
          <label htmlFor={`note-${label}`}>{t('admin.reason')}</label>
          <input id={`note-${label}`} required maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} />
          <div className="actions">
            <button type="submit">{t('admin.confirmReject')}</button>
            <button type="button" className="secondary-button" onClick={() => setRejecting(false)}>{t('common.cancel')}</button>
          </div>
        </form>
      )}
    </>
  );
}

function ReviewQueue() {
  const { t, lang, label } = useI18n();
  const q = useFetch<Queue>('/api/admin/applications');
  if (q.error) return <ErrorBox error={q.error} retry={q.reload} />;
  if (!q.data) return <Loading />;
  const post = (path: string, note?: string) => api(path, { method: 'POST', body: note ? { note } : {} }).then(q.reload);
  return (
    <>
      <h2>{t('admin.pendingRestaurants')} <span className="count">{q.data.restaurants.length}</span></h2>
      {q.data.restaurants.length === 0 ? <p className="muted">{t('admin.nothingPending')}</p> : (
        <ul className="results">
          {q.data.restaurants.map((r) => (
            <li key={r.id} className="card" data-application={r.slug}>
              <h3 className="h4"><Names o={r} /></h3>
              <p>{label(r.cuisine)} · {r.neighborhood} · {r.address}</p>
              <p className="muted">{t('admin.submittedBy', { name: r.ownerName ?? '?', email: r.ownerEmail ?? '?', when: dateTime(r.submittedAt, lang) })}</p>
              <Decision label={r.name} approve={() => post(`/api/admin/restaurants/${r.id}/approve`)}
                reject={(note) => post(`/api/admin/restaurants/${r.id}/reject`, note)} />
            </li>
          ))}
        </ul>
      )}
      <h2>{t('admin.pendingRiders')} <span className="count">{q.data.riders.length}</span></h2>
      {q.data.riders.length === 0 ? <p className="muted">{t('admin.nothingPending')}</p> : (
        <ul className="results">
          {q.data.riders.map((r) => (
            <li key={r.id} className="card" data-rider={r.email}>
              <h3 className="h4">{r.name}</h3>
              <p className="muted">{r.email} · {dateTime(r.signedUpAt, lang)}</p>
              <Decision label={r.name} approve={() => post(`/api/admin/riders/${r.id}/approve`)}
                reject={(note) => post(`/api/admin/riders/${r.id}/reject`, note)} />
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function Restaurants() {
  const { t, names } = useI18n();
  const list = useFetch<Summary[]>('/api/merchant/restaurants');
  const [open, setOpen] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (list.error) return <ErrorBox error={list.error} retry={list.reload} />;
  if (!list.data) return <Loading />;
  const act = (id: number, action: string, note?: string) => {
    setError(null);
    api(`/api/admin/restaurants/${id}/${action}`, { method: 'POST', body: note ? { note } : {} }).then(list.reload).catch((e) => setError(message(e)));
  };
  return (
    <>
      {error && <p className="notice error" role="alert">{error}</p>}
      <table>
        <thead><tr><th scope="col">{t('merchant.restaurant')}</th><th scope="col">{t('admin.status')}</th><th scope="col"><span className="visually-hidden">{t('admin.actions')}</span></th></tr></thead>
        <tbody>
          {list.data.map((r) => (
            <tr key={r.id} data-restaurant={r.slug}>
              <th scope="row">{r.status === 'LIVE' ? <Link to={`/r/${r.slug}`}>{names(r)[0]}</Link> : names(r)[0]}</th>
              <td><span className={`badge rstatus-${r.status.toLowerCase()}`}>{t(`rstatus.${r.status}` as Key)}</span>{r.statusNote ? <span className="muted"> — {r.statusNote}</span> : null}</td>
              <td className="actions">
                {r.status === 'LIVE' && <button type="button" className="secondary-button" onClick={() => {
                  const note = window.prompt(t('admin.suspendPrompt', { name: r.name }));
                  if (note) act(r.id, 'suspend', note);
                }}>{t('admin.suspend')}</button>}
                {r.status === 'SUSPENDED' && <button type="button" className="secondary-button" onClick={() => act(r.id, 'reinstate')}>{t('admin.reinstate')}</button>}
                {r.status === 'LIVE' && <button type="button" className="link-button" onClick={() => setOpen(open === r.id ? null : r.id)} aria-expanded={open === r.id}>{t('admin.moderate')}</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {open !== null && <Moderation key={open} rid={open} />}
    </>
  );
}

function Moderation({ rid }: { rid: number }) {
  const { t } = useI18n();
  const data = useFetch<RestaurantReviews>(`/api/merchant/restaurants/${rid}/reviews?limit=50`);
  if (data.error) return <ErrorBox error={data.error} retry={data.reload} />;
  if (!data.data) return <Loading />;
  return (
    <section aria-labelledby="mod-title">
      <h2 id="mod-title">{t('admin.moderation')} <Stars rating={data.data.rating.average} count={data.data.rating.count} /></h2>
      {data.data.page.items.length === 0 ? <p className="muted">{t('reviews.noneYet')}</p> : (
        <ul className="review-list">
          {data.data.page.items.map((r) => (
            <ReviewItem key={r.orderId} r={r}>
              {r.hidden
                ? <button type="button" className="link-button" onClick={() => void api(`/api/admin/reviews/${r.orderId}/unhide`, { method: 'POST' }).then(data.reload)}>{t('admin.unhide')}</button>
                : <button type="button" className="link-button" onClick={() => {
                  const note = window.prompt(t('admin.hidePrompt'));
                  if (note) void api(`/api/admin/reviews/${r.orderId}/hide`, { method: 'POST', body: { note } }).then(data.reload);
                }}>{t('admin.hide')}</button>}
            </ReviewItem>
          ))}
        </ul>
      )}
    </section>
  );
}

function Operations() {
  const { t, lang } = useI18n();
  const audit = useFetch<Record<string, number | boolean>>('/api/admin/ledger/audit');
  const stats = useFetch<{ deadlocks: number; outboxPending: number; ordersByStatus: Record<string, number>[] }>('/api/admin/stats');
  const log = useFetch<AuditEntry[]>('/api/admin/audit?limit=30');
  const misses = useFetch<SearchMiss[]>('/api/admin/search-misses?limit=30');
  const [msg, setMsg] = useState<string | null>(null);
  return (
    <>
      <section aria-labelledby="ledger-title">
        <h2 id="ledger-title">{t('admin.ledger')}</h2>
        {audit.error ? <ErrorBox error={audit.error} retry={audit.reload} /> : !audit.data ? <Loading /> : (
          <>
            <p className={audit.data.consistent ? 'open' : 'notice error'}>{audit.data.consistent ? t('admin.ledgerOk') : t('admin.ledgerBad')}</p>
            <dl className="totals stats">
              {Object.entries(audit.data).filter(([k]) => k !== 'consistent').map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{String(v)}</dd></div>)}
            </dl>
          </>
        )}
      </section>
      <section aria-labelledby="health-title">
        <h2 id="health-title">{t('admin.health')}</h2>
        {stats.data && (
          <dl className="totals stats">
            <div><dt>{t('admin.deadlocks')}</dt><dd>{stats.data.deadlocks}</dd></div>
            <div><dt>{t('admin.outbox')}</dt><dd>{stats.data.outboxPending}</dd></div>
            {stats.data.ordersByStatus.map((e) => Object.entries(e).map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>))}
          </dl>
        )}
        <button type="button" className="secondary-button" onClick={() => {
          setMsg(null);
          api('/api/admin/search/reindex', { method: 'POST' }).then(() => setMsg(t('admin.reindexed'))).catch((e) => setMsg(message(e)));
        }}>{t('admin.reindex')}</button>
        {msg && <p role="status" className="muted">{msg}</p>}
      </section>
      <section aria-labelledby="misses-title">
        <h2 id="misses-title">{t('admin.misses')}</h2>
        {misses.error ? <ErrorBox error={misses.error} retry={misses.reload} /> : !misses.data ? <Loading /> :
          misses.data.length === 0 ? <p className="muted">{t('admin.missesEmpty')}</p> : (
            <>
              <p className="muted">{t('admin.missesHint')}</p>
              <table>
                <thead><tr><th scope="col">{t('admin.missQuery')}</th><th scope="col">{t('admin.missCount')}</th><th scope="col">{t('admin.missLast')}</th></tr></thead>
                <tbody>{misses.data.map((m) => (
                  <tr key={m.query}><td><Link to={`/search?q=${encodeURIComponent(m.query)}`}>{m.query}</Link></td><td>{m.misses}</td><td>{dateTime(m.lastSeen, lang)}</td></tr>
                ))}</tbody>
              </table>
            </>
          )}
      </section>
      <section aria-labelledby="audit-title">
        <h2 id="audit-title">{t('admin.auditLog')}</h2>
        {log.data && (
          <table>
            <thead><tr><th scope="col">{t('merchant.when')}</th><th scope="col">{t('admin.who')}</th><th scope="col">{t('admin.action')}</th><th scope="col">{t('admin.note')}</th></tr></thead>
            <tbody>{log.data.map((e) => <tr key={e.id}><td>{dateTime(e.at, lang)}</td><td>{e.actor}</td><td><code>{e.action}</code> {e.subject}</td><td>{e.note ?? ''}</td></tr>)}</tbody>
          </table>
        )}
      </section>
    </>
  );
}
