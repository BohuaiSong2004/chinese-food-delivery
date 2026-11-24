import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, openStream } from '../lib/api';
import { useI18n } from '../lib/i18n';
import { km } from '../lib/location';
import type { DispatchOffer, OrderView } from '../lib/types';

type Source = 'gps' | 'demo';
interface Point { lat: number; lng: number }

const DEMO_START: Point = { lat: 34.0866, lng: -118.1212 }; // between the Alhambra and San Gabriel kitchens
const DEMO_STEP_M = 150; // per tick: demo speed, so a delivery plays out in a minute or two

/** Moves a point up to `m` metres towards a target (flat-earth approximation is fine at city scale). */
export function stepTowards(from: Point, to: Point, m: number): Point {
  const dy = (to.lat - from.lat) * 111_320;
  const dx = (to.lng - from.lng) * 111_320 * Math.cos((from.lat * Math.PI) / 180);
  const d = Math.hypot(dx, dy);
  if (d <= m) return to;
  return { lat: from.lat + (dy / d) * (m / 111_320), lng: from.lng + ((dx / d) * m) / (111_320 * Math.cos((from.lat * Math.PI) / 180)) };
}

/**
 * The rider's shift: going online shares the position (GPS, or a demo simulator that drives
 * towards the current pickup or drop-off), and dispatch offers arrive here with a countdown.
 */
export function RiderShift({ active, onChange }: { active: OrderView[]; onChange: () => void }) {
  const { t } = useI18n();
  const [online, setOnline] = useState(false);
  const [source, setSource] = useState<Source>('demo');
  const [offer, setOffer] = useState<DispatchOffer | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const pos = useRef<Point>(DEMO_START);
  const activeRef = useRef(active);
  useEffect(() => { activeRef.current = active; }, [active]);

  const loadOffer = useCallback(() => {
    api<DispatchOffer | undefined>('/api/rider/offers/current').then((o) => setOffer(o ?? null)).catch(() => undefined);
  }, []);

  // offers are pushed on the rider's own stream; also check once on load
  useEffect(() => {
    loadOffer();
    return openStream('/api/me/events', {
      onEvent: (name) => {
        if (name === 'offer') loadOffer();
        if (name === 'status') onChange();
      },
    });
  }, [loadOffer, onChange]);

  // a one-second clock for the countdown, only while an offer is showing
  useEffect(() => {
    if (!offer) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [offer]);

  useEffect(() => {
    if (!online) return;
    let watch: number | null = null;
    const send = (p: Point) => api('/api/rider/location', { method: 'POST', body: p }).catch((e) => setError(e instanceof ApiError ? e.message : String(e)));
    if (source === 'gps' && 'geolocation' in navigator) {
      watch = navigator.geolocation.watchPosition(
        (g) => { pos.current = { lat: g.coords.latitude, lng: g.coords.longitude }; void send(pos.current); },
        (e) => setError(e.message), { enableHighAccuracy: true, maximumAge: 5000 });
    }
    const tick = setInterval(() => {
      if (source !== 'demo') return;
      const job = activeRef.current[0];
      if (job) {
        const target = job.status === 'PICKED_UP' && job.deliveryLat !== undefined && job.deliveryLng !== undefined
          ? { lat: job.deliveryLat, lng: job.deliveryLng }
          : job.restaurant.lat !== undefined && job.restaurant.lng !== undefined ? { lat: job.restaurant.lat, lng: job.restaurant.lng } : null;
        if (target) pos.current = stepTowards(pos.current, target, DEMO_STEP_M);
      }
      void send(pos.current);
    }, 3000);
    return () => {
      clearInterval(tick);
      if (watch !== null) navigator.geolocation.clearWatch(watch);
    };
  }, [online, source]);

  const toggle = async () => {
    setError(null);
    try {
      await api('/api/rider/presence', { method: 'POST', body: online ? { online: false } : { online: true, ...pos.current } });
      setOnline(!online);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  };
  const answer = async (accept: boolean) => {
    if (!offer) return;
    setError(null);
    try {
      await api(`/api/rider/offers/${offer.id}/${accept ? 'accept' : 'decline'}`, { method: 'POST' });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
    setOffer(null);
    onChange();
    loadOffer();
  };
  const left = offer ? Math.max(0, Math.round((Date.parse(offer.expiresAt) - now) / 1000)) : 0;

  return (
    <section aria-labelledby="shift" className="card shift">
      <h2 id="shift" className="h3">{online ? t('shift.online') : t('shift.offline')}</h2>
      <div className="toolbar">
        <label htmlFor="source">{t('shift.source')}</label>
        <select id="source" value={source} disabled={online} onChange={(e) => setSource(e.target.value as Source)}>
          <option value="demo">{t('shift.demo')}</option>
          <option value="gps">{t('shift.gps')}</option>
        </select>
        <button type="button" onClick={() => void toggle()}>{online ? t('shift.goOffline') : t('shift.goOnline')}</button>
      </div>
      {error && <p className="notice error" role="alert">{error}</p>}
      {offer && left > 0 && (
        <div className="offer" role="alertdialog" aria-labelledby="offer-title" aria-describedby="offer-desc">
          <h3 id="offer-title">{t('shift.offer')}</h3>
          <p id="offer-desc">{t('shift.offerDetail', { km: km(offer.distanceM), s: left })}</p>
          <div className="actions">
            <button type="button" onClick={() => void answer(true)}>{t('shift.accept')}</button>
            <button type="button" className="secondary-button" onClick={() => void answer(false)}>{t('shift.decline')}</button>
          </div>
        </div>
      )}
    </section>
  );
}
