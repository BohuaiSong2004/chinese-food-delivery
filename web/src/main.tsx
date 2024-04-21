import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { AuthProvider } from './lib/auth';
import { CartProvider } from './lib/cart';
import { I18nProvider } from './lib/i18n';
import { LocationProvider } from './lib/location';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <I18nProvider>
      <AuthProvider>
        <LocationProvider>
          <CartProvider>
            <App />
          </CartProvider>
        </LocationProvider>
      </AuthProvider>
    </I18nProvider>
  </StrictMode>,
);
