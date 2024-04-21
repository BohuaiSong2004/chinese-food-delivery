import { createBrowserRouter, Link, Navigate, RouterProvider } from 'react-router';
import type { ReactNode } from 'react';
import { Layout } from './components/Layout';
import { Home } from './pages/Home';
import { Search } from './pages/Search';
import { Restaurant } from './pages/Restaurant';
import { Cart } from './pages/Cart';
import { Order } from './pages/Order';
import { Orders } from './pages/Orders';
import { SignIn } from './pages/Auth';
import { Kitchen } from './pages/Kitchen';
import { Rider } from './pages/Rider';
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
      { path: '/cart', element: <Cart /> },
      { path: '/orders', element: <RequireRole roles={['CUSTOMER']}><Orders /></RequireRole> },
      { path: '/orders/:id', element: <RequireRole roles={['CUSTOMER', 'ADMIN']}><Order /></RequireRole> },
      { path: '/signin', element: <SignIn mode="signin" /> },
      { path: '/signup', element: <SignIn mode="signup" /> },
      { path: '/kitchen', element: <RequireRole roles={['MERCHANT', 'ADMIN']}><Kitchen /></RequireRole> },
      { path: '/rider', element: <RequireRole roles={['RIDER']}><Rider /></RequireRole> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);

export function App() {
  return <RouterProvider router={router} />;
}
