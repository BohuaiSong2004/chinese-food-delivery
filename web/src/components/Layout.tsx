import { Link, NavLink, Outlet, useNavigate } from 'react-router';
import { useAuth } from '../lib/auth';
import { useCart } from '../lib/cart';
import { useI18n } from '../lib/i18n';
import { LocationPicker } from './LocationPicker';

export function Layout() {
  const { t, lang, setLang } = useI18n();
  const { user, signOut } = useAuth();
  const { count } = useCart();
  const navigate = useNavigate();

  return (
    <>
      <a className="skip-link" href="#main">{t('skip')}</a>
      <header className="site-header">
        <div className="wrap header-row">
          <Link to="/" className="brand">
            <span className="brand-mark" aria-hidden="true">锅</span>
            <span>Wokflow</span>
          </Link>
          <LocationPicker />
          <nav aria-label={t('nav.primary')} className="nav">
            <NavLink to="/search">{t('nav.search')}</NavLink>
            {user?.role === 'CUSTOMER' && <NavLink to="/orders">{t('nav.orders')}</NavLink>}
            {user?.role === 'CUSTOMER' && <NavLink to="/account">{t('nav.account')}</NavLink>}
            {(user?.role === 'MERCHANT' || user?.role === 'ADMIN') && <NavLink to="/kitchen">{t('nav.kitchen')}</NavLink>}
            {user?.role === 'ADMIN' && <NavLink to="/admin">{t('nav.admin')}</NavLink>}
            {user?.role === 'RIDER' && <NavLink to="/rider">{t('nav.deliveries')}</NavLink>}
            {(!user || user.role === 'CUSTOMER') && <NavLink to="/cart">{t('nav.cart', { n: count })}</NavLink>}
            <button type="button" className="link-button" onClick={() => setLang(lang === 'en' ? 'zh' : 'en')}>
              <span lang={lang === 'en' ? 'zh-CN' : 'en'}>{t('lang.switch')}</span>
              <span className="visually-hidden"> — {t('lang.switchLabel')}</span>
            </button>
            {user ? (
              <button type="button" className="link-button" onClick={() => { void signOut().then(() => navigate('/')); }}>
                {t('nav.signOut')}
              </button>
            ) : (
              <NavLink to="/signin">{t('nav.signIn')}</NavLink>
            )}
          </nav>
        </div>
      </header>
      <main id="main" className="wrap" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="site-footer wrap">
        <nav aria-label={t('nav.footer')} className="footer-links">
          <Link to="/partner">{t('footer.partner')}</Link>
          <Link to="/signup?type=rider">{t('footer.ride')}</Link>
        </nav>
        <p>Wokflow — a 2026 portfolio rebuild. Payments run in a sandbox; no real money moves.</p>
      </footer>
    </>
  );
}
