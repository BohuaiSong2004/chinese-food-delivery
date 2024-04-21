import { Link, useNavigate } from 'react-router';
import { useState } from 'react';
import { api, ApiError, refreshSession } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useI18n } from '../lib/i18n';
import type { RestaurantSettings } from '../lib/types';
import { EMPTY_PROFILE, RestaurantForm } from '../components/RestaurantForm';

/** For restaurant owners: what joining involves, then the application form. */
export function Partner() {
  const { t } = useI18n();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  const apply = async (p: typeof EMPTY_PROFILE) => {
    setError(null);
    try {
      await api<RestaurantSettings>('/api/merchant/applications', { method: 'POST', body: p });
      await refreshSession(); // the new token lists the restaurant, so the kitchen console can open it
      navigate('/kitchen');
    } catch (err) {
      setError(err instanceof ApiError ? Object.values(err.fields ?? {})[0] ?? err.message : String(err));
    }
  };

  return (
    <section className="partner">
      <h1>{t('partner.title')}</h1>
      <p className="lead">{t('partner.lead')}</p>
      <ol className="steps">
        <li>{t('partner.step1')}</li>
        <li>{t('partner.step2')}</li>
        <li>{t('partner.step3')}</li>
      </ol>
      {!user ? (
        <p><Link className="button" to="/signup?type=merchant&next=/partner">{t('partner.signUp')}</Link>{' '}
          <Link to="/signin?next=/partner">{t('partner.haveAccount')}</Link></p>
      ) : user.role !== 'MERCHANT' ? (
        <p className="notice">{t('partner.wrongAccount')}</p>
      ) : (
        <>
          <h2>{t('partner.formTitle')}</h2>
          {error && <p className="notice error" role="alert">{error}</p>}
          <RestaurantForm initial={EMPTY_PROFILE} submitLabel={t('partner.submit')} onSubmit={apply} />
        </>
      )}
    </section>
  );
}
