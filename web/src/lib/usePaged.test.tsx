import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { usePaged } from './usePaged';

afterEach(() => vi.unstubAllGlobals());

describe('usePaged', () => {
  it('loads the first page, appends the next one by cursor, and resets when the path changes', async () => {
    const urls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      urls.push(url);
      const body = url.includes('cursor=')
        ? { items: [3, 4] }
        : url.includes('status=READY') ? { items: [9] } : { items: [1, 2], next: 'MTc=:a/b' };
      return new Response(JSON.stringify(body), { status: 200 });
    }));

    const { result, rerender } = renderHook(({ path }) => usePaged<number>(path), { initialProps: { path: '/api/me/orders' } });
    expect(result.current.items).toBeNull();
    await waitFor(() => expect(result.current.items).toEqual([1, 2]));
    expect(result.current.hasMore).toBe(true);

    await act(() => result.current.more());
    expect(result.current.items).toEqual([1, 2, 3, 4]);
    expect(result.current.hasMore).toBe(false);
    expect(urls[1]).toBe('/api/me/orders?cursor=MTc%3D%3Aa%2Fb'); // the opaque cursor is encoded

    rerender({ path: '/api/admin/orders?status=READY' });
    expect(result.current.items).toBeNull(); // never shows the old list under the new filter
    await waitFor(() => expect(result.current.items).toEqual([9]));
  });

  it('reports a failed page without losing what is shown', async () => {
    let fail = false;
    vi.stubGlobal('fetch', vi.fn(async () => (fail
      ? new Response(JSON.stringify({ code: 'busy', detail: 'down' }), { status: 500 })
      : new Response(JSON.stringify({ items: [1], next: 'c' }), { status: 200 }))));
    const { result } = renderHook(() => usePaged<number>('/api/me/orders'));
    await waitFor(() => expect(result.current.items).toEqual([1]));
    fail = true;
    await act(() => result.current.more());
    expect(result.current.items).toEqual([1]);
    expect(result.current.error?.message).toBe('down');
  });
});
