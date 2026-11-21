import { Link, useNavigate } from 'react-router';
import { adminCampaigns } from '../api/adminCampaigns';
import { useApiQuery } from '../api/useApiQuery';
import { AdminAlert } from '../features/admin/AdminAlert';
import { CampaignForm } from '../features/admin/CampaignForm';
import { StatusBadge } from '../features/admin/StatusBadge';
import '../features/admin/admin.css';
import { formatDateTime } from '../format';

/** 管理後台首頁：所有 Campaign 的列表，以及建立新的 DRAFT Campaign。 */
export function AdminPage() {
  const navigate = useNavigate();
  const query = useApiQuery((signal) => adminCampaigns.list(signal), []);

  return (
    <section className="admin">
      <h1>管理後台</h1>

      <section className="admin-card">
        <h2>Campaign 列表</h2>
        {query.status === 'loading' && <p className="muted">載入中…</p>}
        {query.status === 'error' && <AdminAlert error={query.error} onRetry={query.reload} />}
        {query.status === 'success' && query.data.length === 0 && <p className="muted">尚無 Campaign。</p>}
        {query.status === 'success' && query.data.length > 0 && (
          <div className="admin-table-wrap">
            <table className="admin-table" aria-label="Campaign 列表">
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th scope="col">名稱</th>
                  <th scope="col">Campaign Status</th>
                  <th scope="col">時間窗</th>
                  <th scope="col">Draw Quota</th>
                  <th scope="col">Prize 數</th>
                </tr>
              </thead>
              <tbody>
                {query.data.map((c) => (
                  <tr key={c.id}>
                    <td>{c.id}</td>
                    <td>
                      <Link to={`/admin/campaigns/${c.id}`}>{c.name}</Link>
                    </td>
                    <td>
                      <StatusBadge status={c.status} />
                    </td>
                    <td>
                      {formatDateTime(c.startAt)} ～ {formatDateTime(c.endAt)}
                    </td>
                    <td className="num">{c.drawQuota}</td>
                    <td className="num">{c.prizes.length}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="admin-card">
        <h2>建立 Campaign</h2>
        <p className="muted">新的 Campaign 為 DRAFT，加入 Prize 後即可上線。時間以你的本地時區輸入。</p>
        <CampaignForm
          submitLabel="建立 Campaign"
          onSubmit={async (request) => {
            const created = await adminCampaigns.create(request);
            await navigate(`/admin/campaigns/${created.id}`);
          }}
        />
      </section>
    </section>
  );
}
