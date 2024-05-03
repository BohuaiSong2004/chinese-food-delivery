/** The last few searches, kept on this device only (localStorage), newest first. */
const KEY = 'wf_recent_searches';
export const MAX_RECENT = 5;

export function recentSearches(storage: Pick<Storage, 'getItem'> | undefined = safeStorage()): string[] {
  try {
    const raw = storage?.getItem(KEY);
    const list: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list.filter((x): x is string => typeof x === 'string').slice(0, MAX_RECENT) : [];
  } catch {
    return [];
  }
}

export function rememberSearch(q: string, storage: Pick<Storage, 'getItem' | 'setItem'> | undefined = safeStorage()): string[] {
  const text = q.trim().replace(/\s+/g, ' ');
  if (!text || text.length > 100) return recentSearches(storage);
  const next = [text, ...recentSearches(storage).filter((x) => x.toLowerCase() !== text.toLowerCase())].slice(0, MAX_RECENT);
  try { storage?.setItem(KEY, JSON.stringify(next)); } catch { /* full or disabled: just not remembered */ }
  return next;
}

export function forgetSearches(storage: Pick<Storage, 'removeItem'> | undefined = safeStorage()) {
  try { storage?.removeItem(KEY); } catch { /* nothing to do */ }
}

function safeStorage(): Storage | undefined {
  try { return window.localStorage; } catch { return undefined; } // private modes and sandboxed frames throw
}
