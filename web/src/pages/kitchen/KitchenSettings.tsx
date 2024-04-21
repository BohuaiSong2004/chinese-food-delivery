import { useState } from 'react';
import { useFetch } from '../../lib/useFetch';
import { useI18n } from '../../lib/i18n';
import { api, ApiError, uploadFile } from '../../lib/api';
import type { RestaurantSettings } from '../../lib/types';
import { ErrorBox, Loading } from '../../components/bits';
import { RestaurantForm } from '../../components/RestaurantForm';

/** The restaurant profile (hours, area, fees) and the cover photo. */
export function KitchenSettings({ rid, onChanged }: { rid: number; onChanged: () => void }) {
  const { t } = useI18n();
  const settings = useFetch<RestaurantSettings>(`/api/merchant/restaurants/${rid}/settings`);
  const [msg, setMsg] = useState<{ text: string; error?: boolean } | null>(null);
  if (settings.error) return <ErrorBox error={settings.error} retry={settings.reload} />;
  if (!settings.data) return <Loading />;
  const s = settings.data;
  const fail = (err: unknown) => setMsg({ error: true, text: err instanceof ApiError && err.code === 'stale_version'
    ? t('merchant.stale') : err instanceof ApiError ? Object.values(err.fields ?? {})[0] ?? err.message : String(err) });

  return (
    <>
      {msg && <p className={msg.error ? 'notice error' : 'muted'} role={msg.error ? 'alert' : 'status'}>{msg.text}</p>}
      <section aria-labelledby="cover-title" className="card cover-upload">
        <h2 id="cover-title" className="h3">{t('settings.cover')}</h2>
        <label htmlFor="cover-file">{t('settings.coverLabel')}</label>
        <input id="cover-file" type="file" accept="image/jpeg,image/png,image/webp" onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) uploadFile(`/api/merchant/restaurants/${rid}/image`, f).then(() => setMsg({ text: t('menuEd.photoSaved') })).catch(fail);
        }} />
        <p className="muted">{t('settings.coverHint')}</p>
      </section>
      <RestaurantForm key={s.version} initial={s.profile} submitLabel={t('merchant.save')} onSubmit={async (profile) => {
        try {
          await api(`/api/merchant/restaurants/${rid}/settings`, { method: 'PUT', body: { profile, version: s.version } });
          setMsg({ text: t('merchant.saved') });
          onChanged();
        } catch (err) {
          fail(err);
        }
        settings.reload();
      }} />
    </>
  );
}
