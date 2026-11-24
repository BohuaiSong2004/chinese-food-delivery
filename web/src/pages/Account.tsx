import { useState } from 'react';
import { useI18n } from '../lib/i18n';
import { useDeliverTo } from '../lib/location';
import { api } from '../lib/api';
import { AddressForm } from '../components/AddressForm';
import { Loading } from '../components/bits';
import type { Address } from '../lib/types';

/** A customer's saved delivery addresses. */
export function Account() {
  const { t } = useI18n();
  const { addresses, reloadAddresses, deliverTo, chooseAddress, choose } = useDeliverTo();
  const [editing, setEditing] = useState<number | 'new' | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  const saved = async (a: Address) => {
    setEditing(null);
    const list = await reloadAddresses();
    if (!deliverTo?.addressId || deliverTo.addressId === a.id || list.length === 1) chooseAddress(a);
    setMsg(t('addr.saved'));
  };
  const remove = async (a: Address) => {
    await api(`/api/me/addresses/${a.id}`, { method: 'DELETE' });
    const list = await reloadAddresses();
    if (deliverTo?.addressId === a.id) {
      if (list[0]) chooseAddress(list[0]);
      else choose(null);
    }
    setMsg(t('addr.removed'));
  };

  return (
    <section className="account">
      <h1>{t('account.title')}</h1>
      <h2>{t('account.addresses')}</h2>
      {msg && <p role="status" className="muted">{msg}</p>}
      {addresses === null ? <Loading /> : (
        <ul className="results">
          {addresses.map((a) => (
            <li key={a.id} className="card" data-address={a.id}>
              {editing === a.id ? <AddressForm address={a} onSaved={(x) => void saved(x)} onCancel={() => setEditing(null)} /> : (
                <>
                  <h3 className="h4">{a.label}{deliverTo?.addressId === a.id && <span className="badge"> {t('addr.current')}</span>}</h3>
                  <p>{a.line1}{a.line2 ? `, ${a.line2}` : ''}, {a.city} {a.zip}</p>
                  {a.notes && <p className="muted">{a.notes}</p>}
                  <div className="actions">
                    <button type="button" className="secondary-button" onClick={() => setEditing(a.id)} aria-label={t('addr.editLabel', { name: a.label })}>{t('addr.edit')}</button>
                    <button type="button" className="secondary-button" onClick={() => void remove(a)} aria-label={t('addr.removeLabel', { name: a.label })}>{t('addr.remove')}</button>
                  </div>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {editing === 'new' ? <AddressForm onSaved={(x) => void saved(x)} onCancel={() => setEditing(null)} />
        : <button type="button" onClick={() => setEditing('new')}>{t('addr.add')}</button>}
    </section>
  );
}
