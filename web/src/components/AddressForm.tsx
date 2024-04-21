import { useEffect, useState } from 'react';
import { api, ApiError } from '../lib/api';
import { useI18n } from '../lib/i18n';
import type { Address, Place } from '../lib/types';

let zipCache: Promise<Place[]> | null = null;
const serviceZips = () => (zipCache ??= api<Place[]>('/api/geo/zips'));

/** Add or edit a saved address. The server geocodes it (fees depend on distance, so clients cannot). */
export function AddressForm({ address, onSaved, onCancel }: { address?: Address; onSaved: (a: Address) => void; onCancel?: () => void }) {
  const { t } = useI18n();
  const [label, setLabel] = useState(address?.label ?? t('addr.home'));
  const [line1, setLine1] = useState(address?.line1 ?? '');
  const [line2, setLine2] = useState(address?.line2 ?? '');
  const [zip, setZip] = useState(address?.zip ?? '');
  const [notes, setNotes] = useState(address?.notes ?? '');
  const [zips, setZips] = useState<Place[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const id = address?.id ?? 'new';

  useEffect(() => { serviceZips().then(setZips).catch(() => undefined); }, []);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = { label, line1, line2, zip, notes };
      const saved = address
        ? await api<Address>(`/api/me/addresses/${address.id}`, { method: 'PUT', body })
        : await api<Address>('/api/me/addresses', { method: 'POST', body });
      onSaved(saved);
    } catch (err) {
      setError(err instanceof ApiError ? Object.values(err.fields ?? {})[0] ?? err.message : String(err));
    }
    setBusy(false);
  };

  return (
    <form method="post" className="address-form" onSubmit={(e) => void save(e)}>
      <label htmlFor={`a-label-${id}`}>{t('addr.label')}</label>
      <input id={`a-label-${id}`} required maxLength={30} value={label} onChange={(e) => setLabel(e.target.value)} />
      <label htmlFor={`a-line1-${id}`}>{t('addr.line1')}</label>
      <input id={`a-line1-${id}`} required maxLength={120} autoComplete="address-line1" value={line1} onChange={(e) => setLine1(e.target.value)} />
      <label htmlFor={`a-line2-${id}`}>{t('addr.line2')}</label>
      <input id={`a-line2-${id}`} maxLength={60} autoComplete="address-line2" value={line2} onChange={(e) => setLine2(e.target.value)} />
      <label htmlFor={`a-zip-${id}`}>{t('addr.zip')}</label>
      <input id={`a-zip-${id}`} required inputMode="numeric" pattern="\d{5}" maxLength={5} autoComplete="postal-code"
        list={`zips-${id}`} value={zip} onChange={(e) => setZip(e.target.value)} aria-describedby={`a-zip-hint-${id}`} />
      <datalist id={`zips-${id}`}>{zips.map((z) => <option key={z.zip} value={z.zip}>{z.city}</option>)}</datalist>
      <p id={`a-zip-hint-${id}`} className="muted">{t('addr.zipHint')}</p>
      <label htmlFor={`a-notes-${id}`}>{t('addr.notes')}</label>
      <input id={`a-notes-${id}`} maxLength={200} value={notes} onChange={(e) => setNotes(e.target.value)} />
      {error && <p className="notice error" role="alert">{error}</p>}
      <div className="actions">
        <button type="submit" disabled={busy}>{t('addr.save')}</button>
        {onCancel && <button type="button" className="secondary-button" onClick={onCancel}>{t('common.cancel')}</button>}
      </div>
    </form>
  );
}
