import type { ReactNode } from 'react';
import { Navigate } from 'react-router';
import { useCurrentUser } from './AuthProvider';

/**
 * 只有 ADMIN 看得到內容；其他人直接打開後台路由時導回首頁（轉盤）。
 * 真正的權限檢查在後端（非 ADMIN 呼叫 admin API 會得到 403）。
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const user = useCurrentUser();

  if (!user.isAdmin) return <Navigate to="/" replace />;
  return children;
}
