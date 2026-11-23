import { useState } from 'react';
import { useFetch } from '../lib/useFetch';
import { useLiveRefresh } from '../lib/useLiveList';
import { useI18n, type Key } from '../lib/i18n';
import { api, ApiError } from '../lib/api';
import { dateTime, money, shortId, time } from '../lib/format';
import type { OrderStatus, OrderView } from '../lib/types';
import { ErrorBox, Loading, Names, StatusBadge } from '../components/bits';

interface Summary { id: number; slug: string; name: string; nameZh: string; acceptingOrders: boolean }
interface MerchantDish { id: number; name: string; nameZh: string; priceCents: number; available: boolean; dailyStock?: number; stockRemaining?: number; version: number }
interface Balance { balanceCents: number; pendingRefundCents: number; availableCents: number }
interface Entry { id: number; kind: 'CAPTURE' | 'REFUND' | 'PAYOUT'; orderId?: string; createdAt: string; amountCents: number }

export function Kitchen() {
  const { t, names } = useI18n();
  const mine = useFetch<Summary[]>('/api/merchant/restaurants');
  const [rid, setRid] = useState<number | null>(null);
  const [tab, setTab] = useState<'board' | 'menu' | 'earnings'>('board');

  if (mine.error) return <ErrorBox error={mine.error} retry={mine.reload} />;
  if (!mine.data) return <Loading />;
  if (mine.data.length === 0) return <p>{t('merchant.none')}</p>;
  const current = mine.data.find((r) => r.id === rid) ?? mine.data[0]!;

  const toggle = async () => {
    await api(`/api/merchant/restaurants/${current.id}/${current.acceptingOrders ? 'pause' : 'resume'}`, { method: 'POST' });
    mine.reload();
  };

  return (
    <section className="kitchen">
      <h1>{t('merchant.title')}</h1>
      <div className="toolbar">
        <label htmlFor="restaurant">{t('merchant.restaurant')}</label>
        <select id="restaurant" value={current.id} onChange={(e) => setRid(Number(e.target.value))}>
          {mine.data.map((r) => <option key={r.id} value={r.id}>{names(r)[0]}</option>)}
        </select>
        <button type="button" className="secondary-button" onClick={() => void toggle()}>
          {current.acceptingOrders ? t('merchant.pause') : t('merchant.resume')}
        </button>
      </div>
      {!current.acceptingOrders && <p className="notice">{t('merchant.pausedNotice')}</p>}
      <div role="tablist" aria-label={t('merchant.title')} className="tabs">
        {(['board', 'menu', 'earnings'] as const).map((k) => (
          <button key={k} type="button" role="tab" id={`tab-${k}`} aria-selected={tab === k} aria-controls={`panel-${k}`}
            onClick={() => setTab(k)}>{t(`merchant.${k}` as Key)}</button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {/* keyed by restaurant: switching never leaves the previous kitchen's orders on screen */}
        {tab === 'board' && <Board key={current.id} rid={current.id} />}
        {tab === 'menu' && <Menu key={current.id} rid={current.id} />}
        {tab === 'earnings' && <Earnings key={current.id} rid={current.id} />}
      </div>
    </section>
  );
}

const COLUMNS: OrderStatus[] = ['PAID', 'ACCEPTED', 'READY', 'PICKED_UP'];

function Board({ rid }: { rid: number }) {
  const { t, lang } = useI18n();
  const board = useFetch<OrderView[]>(`/api/merchant/restaurants/${rid}/orders`);
  useLiveRefresh(`/api/merchant/restaurants/${rid}/events`, board.reload);
  const [rejecting, setRejecting] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  const act = async (o: OrderView, action: 'accept' | 'reject' | 'ready', body?: unknown) => {
    setError(null);
    try {
      // If-Match: act only on the version shown, so a stale screen cannot double-act.
      await api(`/api/merchant/orders/${o.id}/${action}`, { method: 'POST', headers: { 'If-Match': `"${o.version}"` }, body });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
    setRejecting(null);
    board.reload();
  };

  if (board.error) return <ErrorBox error={board.error} retry={board.reload} />;
  if (!board.data) return <Loading />;
  const done = board.data.filter((o) => o.status === 'DELIVERED' || o.status === 'REJECTED' || o.status === 'CANCELLED');

  return (
    <>
      {error && <p className="notice error" role="alert">{error}</p>}
      <div className="board">
        {COLUMNS.map((col) => {
          const orders = board.data!.filter((o) => o.status === col);
          return (
            <section key={col} className="column" aria-labelledby={`col-${col}`}>
              <h2 id={`col-${col}`}>{t(`merchant.col.${col}` as Key)} <span className="count">{orders.length}</span></h2>
              {orders.length === 0 ? <p className="muted">{t('merchant.empty')}</p> : (
                <ul>
                  {orders.map((o) => (
                    <li key={o.id} className="card ticket" data-order={o.id}>
                      <h3 className="h4">#{shortId(o.id)} · {o.customerName}</h3>
                      <p className="muted">{time(o.createdAt, lang)} · {money(o.totalCents)}</p>
                      <ul className="ticket-items">{o.items.map((l) => <li key={l.dishId}>{l.quantity} × <Names o={l} /></li>)}</ul>
                      {o.deliveryNotes && <p className="notes">“{o.deliveryNotes}”</p>}
                      {col === 'PAID' && rejecting !== o.id && (
                        <div className="actions">
                          <button type="button" onClick={() => void act(o, 'accept')}>{t('merchant.accept')}</button>
                          <button type="button" className="secondary-button" onClick={() => { setRejecting(o.id); setReason(''); }}>{t('merchant.reject')}</button>
                        </div>
                      )}
                      {rejecting === o.id && (
                        <form method="post" onSubmit={(e) => { e.preventDefault(); void act(o, 'reject', { reason }); }}>
                          <label htmlFor={`reason-${o.id}`}>{t('merchant.rejectReason')}</label>
                          <input id={`reason-${o.id}`} required maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />
                          <button type="submit">{t('merchant.confirmReject')}</button>
                        </form>
                      )}
                      {col === 'ACCEPTED' && <button type="button" onClick={() => void act(o, 'ready')}>{t('merchant.ready')}</button>}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          );
        })}
      </div>
      {done.length > 0 && (
        <section aria-labelledby="done">
          <h2 id="done">{t('merchant.col.done')}</h2>
          <ul className="done-list">
            {done.map((o) => <li key={o.id}>#{shortId(o.id)} · {o.customerName} · {money(o.totalCents)} <StatusBadge status={o.status} /></li>)}
          </ul>
        </section>
      )}
    </>
  );
}

function Menu({ rid }: { rid: number }) {
  const { t } = useI18n();
  const menu = useFetch<{ dishes: MerchantDish[] }>(`/api/merchant/restaurants/${rid}/menu`);
  if (menu.error) return <ErrorBox error={menu.error} retry={menu.reload} />;
  if (!menu.data) return <Loading />;
  return (
    <table className="menu-table">
      <thead>
        <tr>
          <th scope="col">{t('merchant.dish')}</th><th scope="col">{t('merchant.price')}</th>
          <th scope="col">{t('merchant.available')}</th><th scope="col">{t('merchant.dailyStock')}</th>
          <th scope="col">{t('merchant.left')}</th><th scope="col"><span className="visually-hidden">{t('merchant.save')}</span></th>
        </tr>
      </thead>
      <tbody>{menu.data.dishes.map((d) => <DishRow key={`${d.id}-${d.version}`} d={d} onSaved={menu.reload} />)}</tbody>
    </table>
  );
}

function DishRow({ d, onSaved }: { d: MerchantDish; onSaved: () => void }) {
  const { t, names } = useI18n();
  const [price, setPrice] = useState((d.priceCents / 100).toFixed(2));
  const [available, setAvailable] = useState(d.available);
  const [stock, setStock] = useState(d.dailyStock?.toString() ?? '');
  const [msg, setMsg] = useState<string | null>(null);
  const [name] = names(d);
  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await api(`/api/merchant/dishes/${d.id}`, {
        method: 'PATCH',
        body: { priceCents: Math.round(Number(price) * 100), available, dailyStock: stock ? Number(stock) : null, version: d.version },
      });
      setMsg(t('merchant.saved'));
    } catch (err) {
      setMsg(err instanceof ApiError && err.code === 'stale_version' ? t('merchant.stale') : String((err as Error).message));
    }
    onSaved();
  };
  const form = `dish-form-${d.id}`;
  return (
    <tr>
      <th scope="row"><Names o={d} /></th>
      <td><input form={form} aria-label={`${t('merchant.price')} — ${name}`} inputMode="decimal" pattern="\d+(\.\d{1,2})?" required value={price} onChange={(e) => setPrice(e.target.value)} /></td>
      <td><input form={form} type="checkbox" aria-label={`${t('merchant.available')} — ${name}`} checked={available} onChange={(e) => setAvailable(e.target.checked)} /></td>
      <td><input form={form} aria-label={`${t('merchant.dailyStock')} — ${name}`} inputMode="numeric" pattern="\d*" value={stock} onChange={(e) => setStock(e.target.value)} /></td>
      <td>{d.stockRemaining ?? '—'}</td>
      <td>
        <form id={form} method="post" onSubmit={(e) => void save(e)}><button type="submit">{t('merchant.save')}</button></form>
        {msg && <span role="status" className="muted"> {msg}</span>}
      </td>
    </tr>
  );
}

function Earnings({ rid }: { rid: number }) {
  const { t, lang } = useI18n();
  const ledger = useFetch<{ balance: Balance; entries: Entry[] }>(`/api/merchant/restaurants/${rid}/ledger`);
  const [amount, setAmount] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  if (ledger.error) return <ErrorBox error={ledger.error} retry={ledger.reload} />;
  if (!ledger.data) return <Loading />;
  const b = ledger.data.balance;
  const payout = async (e: React.FormEvent) => {
    e.preventDefault();
    const cents = Math.round(Number(amount) * 100);
    try {
      await api(`/api/merchant/restaurants/${rid}/payouts`, { method: 'POST', body: { amountCents: cents } });
      setMsg(t('merchant.payoutDone', { amount: money(cents) }));
      setAmount('');
    } catch (err) {
      setMsg((err as Error).message);
    }
    ledger.reload();
  };
  return (
    <>
      <dl className="totals balance">
        <dt>{t('merchant.balance')}</dt><dd>{money(b.balanceCents)}</dd>
        <dt>{t('merchant.pending')}</dt><dd>{money(b.pendingRefundCents)}</dd>
        <dt>{t('merchant.availableToPay')}</dt><dd><strong>{money(b.availableCents)}</strong></dd>
      </dl>
      <form method="post" onSubmit={(e) => void payout(e)} className="inline-form">
        <label htmlFor="payout">{t('merchant.payoutAmount')}</label>
        <input id="payout" inputMode="decimal" pattern="\d+(\.\d{1,2})?" required value={amount} onChange={(e) => setAmount(e.target.value)} />
        <button type="submit">{t('merchant.payout')}</button>
      </form>
      {msg && <p role="status">{msg}</p>}
      <h2>{t('merchant.entries')}</h2>
      <table>
        <thead><tr><th scope="col">{t('merchant.when')}</th><th scope="col">{t('merchant.what')}</th><th scope="col">{t('merchant.order')}</th><th scope="col">{t('merchant.amount')}</th></tr></thead>
        <tbody>
          {ledger.data.entries.map((e) => (
            <tr key={e.id}>
              <td>{dateTime(e.createdAt, lang)}</td><td>{t(`merchant.entry.${e.kind}` as Key)}</td>
              <td>{e.orderId ? `#${shortId(e.orderId)}` : '—'}</td><td className={e.amountCents < 0 ? 'neg' : ''}>{money(e.amountCents)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
