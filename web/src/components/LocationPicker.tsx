import { useId, useRef, useState } from 'react';
import { Link } from 'react-router';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useI18n } from '../lib/i18n';
import { useDeliverTo } from '../lib/location';
import type { Place } from '../lib/types';

/** "Deliver to …" in the header: saved addresses for customers, a ZIP code for visitors. */
export function LocationPicker() {
  const { t } = useI18n();
  const { user } = useAuth();
  const { deliverTo, addresses, chooseAddress, choose } = useDeliverTo();
  const dialog = useRef<HTMLDialogElement>(null);
  const [zip, setZip] = useState('');
  const [error, setError] = useState<string | null>(null);
  const titleId = useId();
  if (user && user.role !== 'CUSTOMER') return null;

  const open = () => { setError(null); dialog.current?.showModal(); };
  const close = () => dialog.current?.close();
  const applyZip = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const p = await api<Place>(`/api/geo/zips/${encodeURIComponent(zip.trim())}`);
      choose({ label: `${p.zip} ${p.city}`, lat: p.centroid.lat, lng: p.centroid.lng, zip: p.zip });
      close();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err));
    }
  };

  return (
    <>
      <button type="button" className="link-button deliver-to" onClick={open} aria-haspopup="dialog">
        <span aria-hidden="true">📍 </span>{deliverTo ? t('loc.deliverTo', { place: deliverTo.label }) : t('loc.setLocation')}
      </button>
      <dialog ref={dialog} aria-labelledby={titleId} className="picker">
        <h2 id={titleId}>{t('loc.title')}</h2>
        {user?.role === 'CUSTOMER' ? (
          <>
            {addresses && addresses.length > 0 ? (
              <fieldset>
                <legend className="visually-hidden">{t('loc.title')}</legend>
                {addresses.map((a) => (
                  <label key={a.id} className="check">
                    <input type="radio" name="deliver-to" checked={deliverTo?.addressId === a.id}
                      onChange={() => { chooseAddress(a); close(); }} />
                    <span><strong>{a.label}</strong> — {a.line1}, {a.city}</span>
                  </label>
                ))}
              </fieldset>
            ) : <p className="muted">{t('loc.noAddresses')}</p>}
            <p><Link to="/account" onClick={close}>{t('loc.manage')}</Link></p>
          </>
        ) : (
          <form method="get" onSubmit={(e) => void applyZip(e)}>
            <label htmlFor="zip-input">{t('loc.zip')}</label>
            <input id="zip-input" inputMode="numeric" pattern="\d{5}" maxLength={5} required autoComplete="postal-code"
              value={zip} onChange={(e) => setZip(e.target.value)} />
            {error && <p className="notice error" role="alert">{error}</p>}
            <button type="submit">{t('loc.use')}</button>
          </form>
        )}
        <div className="actions">
          {deliverTo && <button type="button" className="secondary-button" onClick={() => { choose(null); close(); }}>{t('loc.clear')}</button>}
          <button type="button" className="secondary-button" onClick={close}>{t('common.close')}</button>
        </div>
      </dialog>
    </>
  );
}
