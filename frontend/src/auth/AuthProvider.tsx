import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { currentUser, initAuth, login, logout, subscribeAuth } from './keycloak';
import type { AuthUser } from './roles';

export type AuthState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'anonymous' }
  | { status: 'authenticated'; user: AuthUser };

export interface AuthContextValue {
  state: AuthState;
  login: () => Promise<void>;
  logout: () => Promise<void>;
}

/** 一般只透過 useAuth() 使用；測試可直接提供 value。 */
export const AuthContext = createContext<AuthContextValue | null>(null);

function snapshot(): AuthState {
  const user = currentUser();
  return user ? { status: 'authenticated', user } : { status: 'anonymous' };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' });

  useEffect(() => {
    let active = true;
    const unsubscribe = subscribeAuth(() => active && setState(snapshot()));
    initAuth()
      .then(() => active && setState(snapshot()))
      .catch(() => active && setState({ status: 'error', message: '無法連線到登入服務（Keycloak），請稍後再試。' }));
    return () => {
      active = false;
      unsubscribe();
    };
  }, []);

  return <AuthContext.Provider value={{ state, login, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}

/** 已登入使用者；只能在 RequireAuth 底下的頁面使用。 */
export function useCurrentUser(): AuthUser {
  const { state } = useAuth();
  if (state.status !== 'authenticated') throw new Error('useCurrentUser used outside an authenticated route');
  return state.user;
}
