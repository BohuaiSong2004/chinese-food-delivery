import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from './api';
import { useAuth } from './auth';
import type { Address } from './types';

/**
 * Where the customer wants food delivered. Signed-in customers pick one of their saved addresses
 * (orders are priced from it on the server); visitors can enter a ZIP to see fees and times.
 * Only the rounded point is sent with browsing requests.
 */
export interface DeliverTo { label: string; lat: number; lng: number; addressId?: number; zip?: string }

interface LocationApi {
  deliverTo: DeliverTo | null;
  addresses: Address[] | null;
  choose: (d: DeliverTo | null) => void;
  chooseAddress: (a: Address) => void;
  reloadAddresses: () => Promise<Address[]>;
  /** "lat=..&lng=.." for API calls, or '' when no point is set. */
  query: string;
}

const KEY = 'wf_deliver_to';
const Ctx = createContext<LocationApi | null>(null);

function load(): DeliverTo | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as DeliverTo) : null;
  } catch {
    return null;
  }
}

export const fromAddress = (a: Address): DeliverTo => ({ label: a.label, lat: a.lat, lng: a.lng, addressId: a.id, zip: a.zip });

export function LocationProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [deliverTo, setDeliverTo] = useState<DeliverTo | null>(load);
  const [addresses, setAddresses] = useState<Address[] | null>(null);

  const choose = useCallback((d: DeliverTo | null) => {
    setDeliverTo(d);
    try {
      if (d) localStorage.setItem(KEY, JSON.stringify(d));
      else localStorage.removeItem(KEY);
    } catch { /* storage unavailable */ }
  }, []);

  const reloadAddresses = useCallback(async () => {
    const list = await api<Address[]>('/api/me/addresses');
    setAddresses(list);
    return list;
  }, []);

  // A customer's saved addresses replace a visitor's ZIP; a stale saved choice (deleted address) is dropped.
  useEffect(() => {
    if (user?.role !== 'CUSTOMER') return;
    let live = true;
    api<Address[]>('/api/me/addresses').then((list) => {
      if (!live) return;
      setAddresses(list);
      const current = load();
      const still = current?.addressId ? list.find((a) => a.id === current.addressId) : undefined;
      if (still) choose(fromAddress(still));
      else if (list[0]) choose(fromAddress(list[0]));
    }).catch(() => undefined);
    return () => { live = false; };
  }, [user, choose]);

  const value = useMemo<LocationApi>(() => ({
    deliverTo: user && user.role !== 'CUSTOMER' ? null : deliverTo,
    addresses: user?.role === 'CUSTOMER' ? addresses : null,
    choose,
    chooseAddress: (a) => choose(fromAddress(a)),
    reloadAddresses,
    query: deliverTo && (!user || user.role === 'CUSTOMER')
      ? `lat=${deliverTo.lat.toFixed(4)}&lng=${deliverTo.lng.toFixed(4)}` : '',
  }), [deliverTo, addresses, choose, reloadAddresses, user]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useDeliverTo(): LocationApi {
  const v = useContext(Ctx);
  if (!v) throw new Error('useDeliverTo outside LocationProvider');
  return v;
}

// Math.round, not toFixed: (0.95).toFixed(1) is "0.9" because 0.95 is stored as 0.9499…
export const km = (m: number) => (m < 950 ? `${Math.max(100, Math.round(m / 100) * 100)} m` : `${(Math.round(m / 100) / 10).toFixed(1)} km`);
