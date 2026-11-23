import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

export interface CartLine { dishId: number; name: string; nameZh: string; priceCents: number; quantity: number }
export interface Cart {
  restaurantId: number; restaurantSlug: string; restaurantName: string; restaurantNameZh: string;
  deliveryFeeCents: number; minOrderCents: number; lines: CartLine[];
}
export interface RestaurantRef {
  id: number; slug: string; name: string; nameZh: string; deliveryFeeCents: number; minOrderCents: number;
}

interface CartApi {
  cart: Cart | null;
  count: number;
  subtotal: number;
  /** Returns false (and changes nothing) when the cart holds another restaurant's dishes. */
  add: (r: RestaurantRef, line: Omit<CartLine, 'quantity'>) => boolean;
  replaceWith: (r: RestaurantRef, line: Omit<CartLine, 'quantity'>) => void;
  setQuantity: (dishId: number, quantity: number) => void;
  clear: () => void;
}

const KEY = 'wf_cart';
const Ctx = createContext<CartApi | null>(null);

function load(): Cart | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Cart) : null;
  } catch {
    return null;
  }
}

export function CartProvider({ children }: { children: ReactNode }) {
  const [cart, setCart] = useState<Cart | null>(load);

  useEffect(() => {
    try {
      if (cart && cart.lines.length) localStorage.setItem(KEY, JSON.stringify(cart));
      else localStorage.removeItem(KEY);
    } catch { /* storage unavailable: the cart still works for this tab */ }
  }, [cart]);

  const fresh = (r: RestaurantRef, line: Omit<CartLine, 'quantity'>): Cart => ({
    restaurantId: r.id, restaurantSlug: r.slug, restaurantName: r.name, restaurantNameZh: r.nameZh,
    deliveryFeeCents: r.deliveryFeeCents, minOrderCents: r.minOrderCents, lines: [{ ...line, quantity: 1 }],
  });

  const add = useCallback((r: RestaurantRef, line: Omit<CartLine, 'quantity'>) => {
    if (cart && cart.lines.length && cart.restaurantId !== r.id) return false;
    setCart((c) => {
      if (!c || c.restaurantId !== r.id || !c.lines.length) return fresh(r, line);
      const existing = c.lines.find((l) => l.dishId === line.dishId);
      const lines = existing
        ? c.lines.map((l) => (l.dishId === line.dishId ? { ...l, quantity: Math.min(50, l.quantity + 1) } : l))
        : [...c.lines, { ...line, quantity: 1 }];
      return { ...c, lines };
    });
    return true;
  }, [cart]);

  const value = useMemo<CartApi>(() => ({
    cart,
    count: cart?.lines.reduce((n, l) => n + l.quantity, 0) ?? 0,
    subtotal: cart?.lines.reduce((n, l) => n + l.quantity * l.priceCents, 0) ?? 0,
    add,
    replaceWith: (r, line) => setCart(fresh(r, line)),
    setQuantity: (dishId, quantity) => setCart((c) => c && {
      ...c,
      lines: c.lines.flatMap((l) => (l.dishId !== dishId ? [l] : quantity <= 0 ? [] : [{ ...l, quantity: Math.min(50, quantity) }])),
    }),
    clear: () => setCart(null),
  }), [cart, add]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useCart(): CartApi {
  const v = useContext(Ctx);
  if (!v) throw new Error('useCart outside CartProvider');
  return v;
}
