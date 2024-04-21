import { useState } from 'react';
import { useFetch } from '../../lib/useFetch';
import { useI18n, type Key } from '../../lib/i18n';
import { api } from '../../lib/api';
import { dateTime, money, shortId } from '../../lib/format';
import { ErrorBox, Loading } from '../../components/bits';

interface Balance { balanceCents: number; pendingRefundCents: number; availableCents: number }
interface Entry { id: number; kind: 'CAPTURE' | 'REFUND' | 'PAYOUT'; orderId?: string; createdAt: string; amountCents: number }

export function Earnings({ rid }: { rid: number }) {
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
