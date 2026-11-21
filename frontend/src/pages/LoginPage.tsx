import { useAuth } from '../auth/AuthProvider';

/** 未登入時顯示：按下後導向 Keycloak（Authorization Code + PKCE）。 */
export function LoginPage() {
  const { login } = useAuth();

  return (
    <main className="login">
      <h1>SpinToWin 轉盤抽獎</h1>
      <p className="muted">請先登入才能參加抽獎。</p>
      <button type="button" className="button" onClick={() => void login()}>
        登入
      </button>
    </main>
  );
}
