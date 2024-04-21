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
  return user.role === 'MERCHANT' || user.role === 'ADMIN' ? '/kitchen' : user.role === 'RIDER' ? '/rider' : '/';
}

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

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const user = signup ? await auth.signUp(email, password, name) : await auth.signIn(email, password);
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
          </ul>
        </div>
      )}
    </section>
  );
}
