import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, refreshSession, setAccessToken, setSessionListener, type Session, type User } from './api';

interface Auth {
  user: User | null;
  ready: boolean;
  signIn: (email: string, password: string) => Promise<User>;
  signUp: (email: string, password: string, name: string) => Promise<User>;
  signOut: () => Promise<void>;
}

const Ctx = createContext<Auth | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    setSessionListener((s) => setUser(s?.user ?? null));
    // Restore a session from the refresh cookie, if there is one.
    void refreshSession().finally(() => setReady(true));
  }, []);

  const start = useCallback((s: Session) => {
    setAccessToken(s.accessToken);
    setUser(s.user);
    return s.user;
  }, []);

  const value = useMemo<Auth>(() => ({
    user,
    ready,
    signIn: async (email, password) => start(await api<Session>('/api/auth/signin', { method: 'POST', body: { email, password } })),
    signUp: async (email, password, name) =>
      start(await api<Session>('/api/auth/signup', { method: 'POST', body: { email, password, name } })),
    signOut: async () => {
      await api('/api/auth/signout', { method: 'POST' }).catch(() => undefined);
      setAccessToken(null);
      setUser(null);
    },
  }), [user, ready, start]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): Auth {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth outside AuthProvider');
  return v;
}
