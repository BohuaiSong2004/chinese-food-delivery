import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useFetch } from './useFetch';
import { keepOnly } from '../pages/Search';

afterEach(() => vi.unstubAllGlobals());

describe('useFetch', () => {
  it('shows nothing once the path becomes null (e.g. the previous user signed out)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{ dishId: 1 }]), { status: 200 })));
    const { result, rerender } = renderHook(({ path }: { path: string | null }) => useFetch<unknown[]>(path),
      { initialProps: { path: '/api/me/reorder' as string | null } });
    await waitFor(() => expect(result.current.data).toEqual([{ dishId: 1 }]));
    rerender({ path: null });
    expect(result.current.data).toBeNull();
    expect(result.current.loading).toBe(false);
  });
});

describe('clearing search filters', () => {
  it('keeps the words and the sort order, drops the rest', () => {
    const p = new URLSearchParams('q=noodles&tag=vegan&tag=halal&cuisine=sichuan&maxSpice=1&sort=price');
    expect(keepOnly(p, ['q', 'sort']).toString()).toBe('q=noodles&sort=price');
    expect(keepOnly(new URLSearchParams('tag=vegan'), ['q', 'sort']).toString()).toBe('');
  });
});
