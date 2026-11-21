import type { ReactNode } from 'react';
import { LoginPage } from '../pages/LoginPage';
import { useAuth } from './AuthProvider';

/** 未登入顯示登入頁；登入服務無法連線時顯示錯誤。 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { state } = useAuth();

  switch (state.status) {
    case 'loading':
      return <p className="muted center">載入中…</p>;
    case 'error':
      return (
        <div className="error-message center" role="alert">
          <strong className="error-message__title">{state.message}</strong>
        </div>
      );
    case 'anonymous':
      return <LoginPage />;
    case 'authenticated':
      return children;
  }
}
