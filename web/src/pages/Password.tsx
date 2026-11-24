import { Link, useNavigate } from 'react-router';
import { useEffect, useState } from 'react';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useI18n } from '../lib/i18n';

function message(err: unknown) {
  return err instanceof ApiError ? Object.values(err.fields ?? {})[0] ?? err.message : String(err);
}

/** Asks for a reset link. The answer is the same whether or not the address has an account. */
export function ForgotPassword() {
  const { t, lang } = useI18n();
  const [email, setEmail] = useState('');
  const [state, setState] = useState<'idle' | 'busy' | 'sent'>('idle');
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setState('busy');
    setError(null);
    try {
      await api('/api/auth/forgot', { method: 'POST', body: { email }, headers: { 'Accept-Language': lang === 'zh' ? 'zh-CN' : 'en' } });
      setState('sent');
    } catch (err) {
      setError(message(err));
      setState('idle');
    }
  };

  return (
    <section className="auth">
      <h1>{t('pw.forgotTitle')}</h1>
      {state === 'sent' ? (
        <p className="notice" role="status">{t('pw.sent', { email })}</p>
      ) : (
        <form method="post" onSubmit={submit}>
          <p className="muted">{t('pw.forgotIntro')}</p>
          <label htmlFor="email">{t('auth.email')}</label>
          <input id="email" type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          {error && <p className="notice error" role="alert">{error}</p>}
          <button type="submit" disabled={state === 'busy'}>{state === 'busy' ? t('auth.working') : t('pw.sendLink')}</button>
        </form>
      )}
      <p><Link to="/signin">{t('pw.backToSignIn')}</Link></p>
    </section>
  );
}

/** Reads the token from the URL fragment (never sent to a server or a Referer), then drops it from history. */
export function readResetToken(hash: string): string | null {
  const token = new URLSearchParams(hash.replace(/^#/, '')).get('token');
  return token && /^[A-Za-z0-9_-]{20,100}$/.test(token) ? token : null;
}

export function ResetPassword() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [token] = useState(() => readResetToken(window.location.hash));
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (window.location.hash) window.history.replaceState(null, '', window.location.pathname);
  }, []);

  if (!token) {
    return (
      <section className="auth">
        <h1>{t('pw.resetTitle')}</h1>
        <p className="notice error" role="alert">{t('pw.badLink')}</p>
        <p><Link to="/forgot">{t('pw.askAgain')}</Link></p>
      </section>
    );
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (password !== confirm) { setError(t('pw.mismatch')); return; }
    setBusy(true);
    setError(null);
    try {
      await api('/api/auth/reset', { method: 'POST', body: { token, password } });
      navigate('/signin?reset=1', { replace: true });
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'invalid_reset_link' ? t('pw.badLink') : message(err));
      setBusy(false);
    }
  };

  return (
    <section className="auth">
      <h1>{t('pw.resetTitle')}</h1>
      <form method="post" onSubmit={submit}>
        <label htmlFor="new-password">{t('pw.new')}</label>
        <input id="new-password" type="password" required minLength={10} maxLength={128} autoComplete="new-password"
          value={password} onChange={(e) => setPassword(e.target.value)} aria-describedby="pw-hint" />
        <p id="pw-hint" className="muted">{t('auth.passwordHint')}</p>
        <label htmlFor="confirm-password">{t('pw.confirm')}</label>
        <input id="confirm-password" type="password" required minLength={10} maxLength={128} autoComplete="new-password"
          value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        {error && <p className="notice error" role="alert">{error}</p>}
        <button type="submit" disabled={busy}>{busy ? t('auth.working') : t('pw.save')}</button>
      </form>
      {error === t('pw.badLink') && <p><Link to="/forgot">{t('pw.askAgain')}</Link></p>}
    </section>
  );
}

/** Signed-in change. Every session ends server-side, so this browser signs straight back in with the new password. */
export function ChangePassword() {
  const { t } = useI18n();
  const auth = useAuth();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api('/api/auth/password', { method: 'POST', body: { currentPassword: current, newPassword: next } });
      await auth.signIn(auth.user!.email, next);
      setCurrent('');
      setNext('');
      setDone(true);
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'wrong_password' ? t('pw.wrongCurrent') : message(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="auth">
      <h1>{t('pw.changeTitle')}</h1>
      <form method="post" onSubmit={submit}>
        <label htmlFor="current-password">{t('pw.current')}</label>
        <input id="current-password" type="password" required maxLength={128} autoComplete="current-password"
          value={current} onChange={(e) => setCurrent(e.target.value)} />
        <label htmlFor="new-password">{t('pw.new')}</label>
        <input id="new-password" type="password" required minLength={10} maxLength={128} autoComplete="new-password"
          value={next} onChange={(e) => setNext(e.target.value)} aria-describedby="pw-hint" />
        <p id="pw-hint" className="muted">{t('pw.changeHint')}</p>
        {error && <p className="notice error" role="alert">{error}</p>}
        {done && <p className="notice" role="status">{t('pw.changed')}</p>}
        <button type="submit" disabled={busy}>{busy ? t('auth.working') : t('pw.save')}</button>
      </form>
    </section>
  );
}
