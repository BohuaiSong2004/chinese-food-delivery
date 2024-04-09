import { describe, expect, it, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { CartProvider, useCart } from './cart';

const wrapper = ({ children }: { children: ReactNode }) => <CartProvider>{children}</CartProvider>;
const a = { id: 1, slug: 'a', name: 'A', nameZh: '甲', deliveryFeeCents: 300, minOrderCents: 1000 };
const b = { ...a, id: 2, slug: 'b', name: 'B' };
const dish = (id: number, price = 500) => ({ dishId: id, name: `D${id}`, nameZh: `菜${id}`, priceCents: price });

describe('cart', () => {
  beforeEach(() => localStorage.clear());

  it('adds, merges quantities, totals and removes', () => {
    const { result } = renderHook(() => useCart(), { wrapper });
    act(() => { result.current.add(a, dish(1)); });
    act(() => { result.current.add(a, dish(1)); });
    act(() => { result.current.add(a, dish(2, 250)); });
    expect(result.current.count).toBe(3);
    expect(result.current.subtotal).toBe(1250);
    act(() => result.current.setQuantity(1, 0));
    expect(result.current.cart?.lines.map((l) => l.dishId)).toEqual([2]);
  });

  it('never mixes restaurants silently', () => {
    const { result } = renderHook(() => useCart(), { wrapper });
    act(() => { result.current.add(a, dish(1)); });
    let added = true;
    act(() => { added = result.current.add(b, dish(9)); });
    expect(added).toBe(false);
    expect(result.current.cart?.restaurantId).toBe(1);
    act(() => result.current.replaceWith(b, dish(9)));
    expect(result.current.cart?.restaurantId).toBe(2);
    expect(result.current.count).toBe(1);
  });

  it('survives a reload', () => {
    const first = renderHook(() => useCart(), { wrapper });
    act(() => { first.result.current.add(a, dish(1)); });
    first.unmount();
    const second = renderHook(() => useCart(), { wrapper });
    expect(second.result.current.count).toBe(1);
  });
});
