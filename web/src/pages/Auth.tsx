import { Link, useNavigate, useSearchParams } from 'react-router';
import { useState } from 'react';
import { useAuth } from '../lib/auth';
import { useI18n } from '../lib/i18n';
import { ApiError, type User } from '../lib/api';

/** Only same-site relative paths, so ?next= cannot be used as an open redirect. */
export function safeNext(next: string | null): string | null {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.includes('\\') || [...next].some((c) => c.charCodeAt(0) < 0x20)) return null;
  return next;
}

function home(user: User) {
  if (user.role === 'ADMIN') return '/admin';
  if (user.role === 'MERCHANT') return user.restaurantIds.length ? '/kitchen' : '/partner';
  return user.role === 'RIDER' ? '/rider' : '/';
}

type AccountType = 'CUSTOMER' | 'MERCHANT' | 'RIDER';
const TYPES: Record<string, AccountType> = { merchant: 'MERCHANT', rider: 'RIDER' };

export function SignIn({ mode }: { mode: 'signin' | 'signup' }) {
  const { t } = useI18n();
  const auth = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const signup = mode === 'signup';
  const [type, setType] = useState<AccountType>(TYPES[params.get('type') ?? ''] ?? 'CUSTOMER');

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const user = signup ? await auth.signUp(email, password, name, type) : await auth.signIn(email, password);
      navigate(safeNext(params.get('next')) ?? home(user), { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? Object.values(err.fields ?? {})[0] ?? err.message : String(err));
      setBusy(false);
    }
  };

  return (
    <section className="auth">
      <h1>{signup ? t('auth.signUp') : t('auth.signIn')}</h1>
      <form method="post" onSubmit={submit}>
        {signup && (
          <fieldset className="account-type">
            <legend>{t('auth.type')}</legend>
            {(['CUSTOMER', 'MERCHANT', 'RIDER'] as const).map((k) => (
              <label key={k} className="check">
                <input type="radio" name="account-type" checked={type === k} onChange={() => setType(k)} />
                {t(`auth.type.${k}`)}
              </label>
            ))}
            {type === 'RIDER' && <p className="muted">{t('auth.riderNote')}</p>}
          </fieldset>
        )}
        {signup && (<><label htmlFor="name">{t('auth.name')}</label>
          <input id="name" required maxLength={60} autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} /></>)}
        <label htmlFor="email">{t('auth.email')}</label>
        <input id="email" type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        <label htmlFor="password">{t('auth.password')}</label>
        <input id="password" type="password" required minLength={signup ? 10 : undefined} maxLength={128}
          autoComplete={signup ? 'new-password' : 'current-password'} value={password} onChange={(e) => setPassword(e.target.value)}
          aria-describedby={signup ? 'pw-hint' : undefined} />
        {signup && <p id="pw-hint" className="muted">{t('auth.passwordHint')}</p>}
        {error && <p className="notice error" role="alert">{error}</p>}
        <button type="submit" disabled={busy}>{busy ? t('auth.working') : signup ? t('auth.signUp') : t('auth.signIn')}</button>
      </form>
      <p>{signup ? <Link to="/signin">{t('auth.haveAccount')}</Link> : <Link to="/signup">{t('auth.noAccount')}</Link>}</p>
      {!signup && (
        <div className="demo">
          <p>{t('auth.demo')}</p>
          <ul>
            <li><code>customer@example.com</code></li>
            <li><code>merchant@example.com</code></li>
            <li><code>rider@example.com</code></li>
            <li><code>admin@example.com</code></li>
          </ul>
        </div>
      )}
    </section>
  );
}
