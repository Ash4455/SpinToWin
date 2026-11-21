import { Link, NavLink, Outlet } from 'react-router';
import { useAuth, useCurrentUser } from '../auth/AuthProvider';
import { config } from '../config';

/** 已登入頁面的外框：頂部導覽（依角色顯示管理後台入口）、使用者名稱與登出。 */
export function Layout() {
  const { logout } = useAuth();
  const user = useCurrentUser();

  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="topbar__brand">
          SpinToWin
        </Link>
        <nav className="topbar__nav">
          <NavLink to={`/campaigns/${config.defaultCampaignId}`}>轉盤抽獎</NavLink>
          {user.isAdmin && <NavLink to="/admin">管理後台</NavLink>}
        </nav>
        <div className="topbar__user">
          <span>{user.username}</span>
          <button type="button" className="button button--secondary" onClick={() => void logout()}>
            登出
          </button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
