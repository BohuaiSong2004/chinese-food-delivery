/**
 * Lazy pages after an upgrade. A tab opened before a new version was deployed (a kitchen tablet left
 * on all day) asks for page chunks whose hashed names no longer exist, and the import fails. Reload
 * once to pick up the new index.html; if that just happened, give up and let the error show, rather
 * than reloading forever (e.g. when the server is simply down).
 */
const KEY = 'wf-chunk-reload-at';
const WINDOW_MS = 10_000;

export function reloadOnce(
  now = Date.now(),
  storage: Pick<Storage, 'getItem' | 'setItem'> | null = safeSession(),
  reload: () => void = () => window.location.reload(),
): boolean {
  const last = Number(storage?.getItem(KEY) ?? 0);
  if (now - last < WINDOW_MS) return false;
  try {
    storage?.setItem(KEY, String(now));
  } catch {
    // storage full or blocked: still reload once
  }
  reload();
  return true;
}

export function retryImport<T>(load: () => Promise<T>, onFail: () => boolean = () => reloadOnce()): Promise<T> {
  return load().catch((err: unknown) => {
    if (onFail()) return new Promise<T>(() => {}); // the page is reloading
    throw err;
  });
}

function safeSession(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}
