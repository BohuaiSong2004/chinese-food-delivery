import { createBrowserRouter, Link, Navigate, RouterProvider } from 'react-router';
import { lazy, Suspense, type ReactNode } from 'react';
import { retryImport } from './lib/retryImport';
import { Layout } from './components/Layout';
import { Home } from './pages/Home';
import { Search } from './pages/Search';
import { Restaurant } from './pages/Restaurant';
import { SignIn } from './pages/Auth';

// Pages most visitors never open are split out, so the browse path downloads less JavaScript.
const Cart = lazy(() => retryImport(() => import('./pages/Cart')).then((m) => ({ default: m.Cart })));
const Order = lazy(() => retryImport(() => import('./pages/Order')).then((m) => ({ default: m.Order })));
const Orders = lazy(() => retryImport(() => import('./pages/Orders')).then((m) => ({ default: m.Orders })));
const Kitchen = lazy(() => retryImport(() => import('./pages/Kitchen')).then((m) => ({ default: m.Kitchen })));
const Rider = lazy(() => retryImport(() => import('./pages/Rider')).then((m) => ({ default: m.Rider })));
const Account = lazy(() => retryImport(() => import('./pages/Account')).then((m) => ({ default: m.Account })));
const Partner = lazy(() => retryImport(() => import('./pages/Partner')).then((m) => ({ default: m.Partner })));
const Password = () => retryImport(() => import('./pages/Password'));
const ForgotPassword = lazy(() => Password().then((m) => ({ default: m.ForgotPassword })));
const ResetPassword = lazy(() => Password().then((m) => ({ default: m.ResetPassword })));
const ChangePassword = lazy(() => Password().then((m) => ({ default: m.ChangePassword })));
const Admin = lazy(() => retryImport(() => import('./pages/Admin')).then((m) => ({ default: m.Admin })));
const page = (el: ReactNode) => <Suspense fallback={<Loading />}>{el}</Suspense>;
import { useAuth } from './lib/auth';
import { useI18n } from './lib/i18n';
import type { Role } from './lib/api';
import { Loading } from './components/bits';

function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { user, ready } = useAuth();
  if (!ready) return <Loading />;
  if (!user) return <Navigate to={`/signin?next=${encodeURIComponent(window.location.pathname + window.location.search)}`} replace />;
  if (!roles.includes(user.role)) return <Navigate to="/" replace />;
  return children;
}

function NotFound() {
  const { t } = useI18n();
  return (<section><h1>{t('common.notFound')}</h1><Link to="/">{t('common.home')}</Link></section>);
}

const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { path: '/', element: <Home /> },
      { path: '/search', element: <Search /> },
      { path: '/r/:slug', element: <Restaurant /> },
      { path: '/cart', element: page(<Cart />) },
      { path: '/orders', element: <RequireRole roles={['CUSTOMER']}>{page(<Orders />)}</RequireRole> },
      { path: '/orders/:id', element: <RequireRole roles={['CUSTOMER', 'ADMIN']}>{page(<Order />)}</RequireRole> },
      { path: '/signin', element: <SignIn mode="signin" /> },
      { path: '/signup', element: <SignIn mode="signup" /> },
      { path: '/forgot', element: page(<ForgotPassword />) },
      { path: '/reset', element: page(<ResetPassword />) },
      { path: '/password', element: <RequireRole roles={['CUSTOMER', 'MERCHANT', 'RIDER', 'ADMIN']}>{page(<ChangePassword />)}</RequireRole> },
      { path: '/kitchen', element: <RequireRole roles={['MERCHANT', 'ADMIN']}>{page(<Kitchen />)}</RequireRole> },
      { path: '/rider', element: <RequireRole roles={['RIDER']}>{page(<Rider />)}</RequireRole> },
      { path: '/account', element: <RequireRole roles={['CUSTOMER']}>{page(<Account />)}</RequireRole> },
      { path: '/partner', element: page(<Partner />) },
      { path: '/admin', element: <RequireRole roles={['ADMIN']}>{page(<Admin />)}</RequireRole> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);

export function App() {
  return <RouterProvider router={router} />;
}
