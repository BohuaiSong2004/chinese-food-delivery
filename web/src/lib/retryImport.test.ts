import { describe, expect, it, vi } from 'vitest';
import { reloadOnce, retryImport } from './retryImport';

function memory() {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) };
}

describe('reloading after a deploy', () => {
  it('reloads once when a page chunk is gone, then lets the error through instead of looping', () => {
    const storage = memory();
    const reload = vi.fn();
    expect(reloadOnce(1_000_000, storage, reload)).toBe(true);
    expect(reloadOnce(1_005_000, storage, reload)).toBe(false); // the reload did not help: stop
    expect(reloadOnce(1_020_000, storage, reload)).toBe(true); // much later, another deploy
    expect(reload).toHaveBeenCalledTimes(2);
  });

  it('passes successful imports through and rethrows when it will not reload', async () => {
    await expect(retryImport(() => Promise.resolve('page'))).resolves.toBe('page');
    await expect(retryImport(() => Promise.reject(new Error('chunk 404')), () => false)).rejects.toThrow('chunk 404');
  });
});
