import { Navigate, Outlet, type RouteObject } from 'react-router';
import { RequireAdmin } from './auth/RequireAdmin';
import { RequireAuth } from './auth/RequireAuth';
import { Layout } from './components/Layout';
import { config } from './config';
import { AdminCampaignPage } from './pages/AdminCampaignPage';
import { AdminPage } from './pages/AdminPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { WheelPage } from './pages/WheelPage';

/** 新增頁面：在 pages/ 建元件，再於此加一筆 route（管理功能放在 admin 底下）。 */
export const routes: RouteObject[] = [
  {
    element: (
      <RequireAuth>
        <Layout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to={`/campaigns/${config.defaultCampaignId}`} replace /> },
      { path: 'campaigns/:campaignId', element: <WheelPage /> },
      {
        path: 'admin',
        element: (
          <RequireAdmin>
            <Outlet />
          </RequireAdmin>
        ),
        children: [
          { index: true, element: <AdminPage /> },
          { path: 'campaigns/:campaignId', element: <AdminCampaignPage /> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
