import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { adminCampaigns, type AdminCampaign, type CampaignStats } from '../api/adminCampaigns';
import { useApiQuery } from '../api/useApiQuery';
import { AdminAlert } from '../features/admin/AdminAlert';
import { CampaignForm } from '../features/admin/CampaignForm';
import { campaignActions } from '../features/admin/campaignActions';
import { AddPrizeForm, EditPrizeForm, StockDeltaControl } from '../features/admin/PrizeForms';
import { ProbabilitySummary } from '../features/admin/ProbabilitySummary';
import { percentToPpm, ppmToPercentText } from '../features/admin/probability';
import { StatusBadge } from '../features/admin/StatusBadge';
import { useAction } from '../features/admin/useAction';
import '../features/admin/admin.css';
import { formatDateTime } from '../format';

/** 管理單一 Campaign：設定、上線／結束、Prize 與 Stock、統計。 */
export function AdminCampaignPage() {
  const { campaignId = '' } = useParams();
  const query = useApiQuery((signal) => adminCampaigns.get(campaignId, signal), [campaignId]);
  // 寫入後以最新的回應取代載入的資料，不讓整頁回到「載入中」
  const [latest, setLatest] = useState<AdminCampaign | null>(null);
  const campaign = latest?.id === Number(campaignId) ? latest : query.data;

  if (query.status === 'loading' && !campaign) return <p className="muted">載入中…</p>;
  if (query.status === 'error') {
    return (
      <section className="admin">
        <BackLink />
        <AdminAlert error={query.error} onRetry={query.reload} />
      </section>
    );
  }
  if (!campaign) return null;
  return <CampaignConsole key={campaign.id} campaign={campaign} onChange={setLatest} />;
}

function BackLink() {
  return (
    <Link to="/admin" className="admin__back">
      ← 所有 Campaign
    </Link>
  );
}

interface ConsoleProps {
  campaign: AdminCampaign;
  onChange: (campaign: AdminCampaign) => void;
}

function CampaignConsole({ campaign, onChange }: ConsoleProps) {
  const id = campaign.id;
  const allowed = campaignActions(campaign.status);
  const stats = useApiQuery((signal) => adminCampaigns.stats(id, signal), [id]);
  const statusAction = useAction();
  const [notice, setNotice] = useState<string | null>(null);
  const [editingPrizeId, setEditingPrizeId] = useState<number | null>(null);
  const editingPrize = campaign.prizes.find((p) => p.id === editingPrizeId);

  const refresh = async () => onChange(await adminCampaigns.get(id));

  async function changeStatus(run: () => Promise<AdminCampaign>, done: string, confirmText?: string) {
    if (confirmText && !window.confirm(confirmText)) return;
    setNotice(null);
    if (await statusAction.run(async () => onChange(await run()))) setNotice(done);
  }

  async function deletePrize(prizeId: number, name: string) {
    if (!window.confirm(`確定要刪除 Prize「${name}」嗎？`)) return;
    setNotice(null);
    await statusAction.run(async () => {
      await adminCampaigns.deletePrize(id, prizeId);
      await refresh();
    });
  }

  return (
    <section className="admin">
      <BackLink />
      <header className="admin__header">
        <h1>{campaign.name}</h1>
        <div className="campaign__meta">
          <StatusBadge status={campaign.status} />
          <span>
            {formatDateTime(campaign.startAt)} ～ {formatDateTime(campaign.endAt)}
          </span>
          <span>Draw Quota：每人 {campaign.drawQuota} 次</span>
        </div>
      </header>

      <div className="admin-actions">
        <button
          type="button"
          className="button"
          disabled={!allowed.activate || statusAction.pending}
          onClick={() => void changeStatus(() => adminCampaigns.activate(id), 'Campaign 已上線。')}
        >
          上線
        </button>
        <button
          type="button"
          className="button button--secondary"
          disabled={!allowed.end || statusAction.pending}
          onClick={() =>
            void changeStatus(() => adminCampaigns.end(id), 'Campaign 已結束。', `確定要結束「${campaign.name}」嗎？結束後無法再上線。`)
          }
        >
          結束
        </button>
        <button
          type="button"
          className="button button--secondary"
          disabled={!allowed.rebuildLiveState || statusAction.pending}
          title="Redis 遺失資料後，以 MySQL 的設定與剩餘 Stock 重建；Redis 正常時不需要使用"
          onClick={() =>
            void changeStatus(
              () => adminCampaigns.rebuildLiveState(id),
              '已從 MySQL 重建 Redis live state。',
              '只在 Redis 遺失資料後使用：尚未寫回的抽獎扣減會被加回 Stock。確定要重建嗎？',
            )
          }
        >
          重建 Redis live state
        </button>
      </div>
      <AdminAlert error={statusAction.error} />
      {notice && (
        <p className="admin-notice" role="status">
          {notice}
        </p>
      )}

      <section className="admin-card">
        <h2>Prize</h2>
        <ProbabilitySummary prizePpms={campaign.prizes.map((p) => percentToPpm(p.winProbability))} />
        {campaign.prizes.length === 0 ? (
          <p className="muted">尚無 Prize；至少需要一個 Prize 才能上線。</p>
        ) : (
          <PrizeTable
            campaign={campaign}
            stats={stats.status === 'success' ? stats.data : undefined}
            onEdit={setEditingPrizeId}
            onDelete={(prizeId, name) => void deletePrize(prizeId, name)}
            onAdjustStock={async (prizeId, delta) => {
              await adminCampaigns.adjustStock(id, prizeId, delta);
              await refresh();
            }}
          />
        )}
        {editingPrize && allowed.editPrize && (
          <EditPrizeForm
            key={editingPrize.id}
            prize={editingPrize}
            prizes={campaign.prizes}
            onCancel={() => setEditingPrizeId(null)}
            onSave={async (body) => {
              await adminCampaigns.updatePrize(id, editingPrize.id, body);
              await refresh();
              setEditingPrizeId(null);
            }}
          />
        )}
        {allowed.addPrize ? (
          <>
            <h3>新增 Prize</h3>
            <AddPrizeForm
              prizes={campaign.prizes}
              onAdd={async (body) => {
                await adminCampaigns.addPrize(id, body);
                await refresh();
              }}
            />
          </>
        ) : (
          <p className="muted">只有 DRAFT 的 Campaign 可以新增或刪除 Prize；ACTIVE 時仍可修改名稱、Win Probability 與 Stock。</p>
        )}
      </section>

      <section className="admin-card">
        <div className="admin-card__title">
          <h2>統計</h2>
          <button
            type="button"
            className="button button--secondary button--small"
            onClick={() => {
              stats.reload();
              void refresh().catch(() => undefined);
            }}
          >
            重新整理
          </button>
        </div>
        {stats.status === 'loading' && <p className="muted">載入中…</p>}
        {stats.status === 'error' && <AdminAlert error={stats.error} onRetry={stats.reload} />}
        {stats.status === 'success' && (
          <dl className="admin-stats">
            <div>
              <dt>總抽獎次數</dt>
              <dd data-testid="total-draws">{stats.data.totalDraws}</dd>
            </div>
            <div>
              <dt>銘謝惠顧</dt>
              <dd data-testid="no-win-count">{stats.data.noWinCount}</dd>
            </div>
          </dl>
        )}
        <p className="muted">各 Prize 的抽出數見上方 Prize 列表（沒抽出過的顯示 0）；統計在抽獎後約 1 秒內更新，ACTIVE 活動的剩餘 Stock 是即時的。</p>
      </section>

      <section className="admin-card">
        <h2>活動設定</h2>
        <CampaignForm
          key={campaign.status}
          campaign={campaign}
          submitLabel="儲存設定"
          disabled={!allowed.editCampaign}
          disabledReason="只有 DRAFT 的 Campaign 可以修改設定。"
          onSubmit={async (request) => onChange(await adminCampaigns.update(id, request))}
        />
      </section>
    </section>
  );
}

interface TableProps {
  campaign: AdminCampaign;
  stats?: CampaignStats;
  onEdit: (prizeId: number) => void;
  onDelete: (prizeId: number, name: string) => void;
  onAdjustStock: (prizeId: number, delta: number) => Promise<unknown>;
}

function PrizeTable({ campaign, stats, onEdit, onDelete, onAdjustStock }: TableProps) {
  const allowed = campaignActions(campaign.status);
  // stats 只列出抽出過的 Prize；沒抽出過的顯示 0
  const drawn = new Map(stats?.prizes.map((p) => [p.prizeId, p.drawnCount]));

  return (
    <div className="admin-table-wrap">
      <table className="admin-table" aria-label="Prize 列表">
        <thead>
          <tr>
            <th scope="col">名稱</th>
            <th scope="col">Win Probability</th>
            <th scope="col">剩餘 / 總 Stock</th>
            <th scope="col">抽出數</th>
            {allowed.adjustStock && <th scope="col">Stock 增減</th>}
            {(allowed.editPrize || allowed.deletePrize) && <th scope="col">操作</th>}
          </tr>
        </thead>
        <tbody>
          {campaign.prizes.map((p) => (
            <tr key={p.id}>
              <td>{p.name}</td>
              <td className="num">{ppmToPercentText(percentToPpm(p.winProbability))}%</td>
              <td className="num">{`${p.remainingStock} / ${p.totalStock}`}</td>
              <td className="num">{stats ? (drawn.get(p.id) ?? 0) : '—'}</td>
              {allowed.adjustStock && (
                <td>
                  <StockDeltaControl prize={p} onAdjust={(delta) => onAdjustStock(p.id, delta)} />
                </td>
              )}
              {(allowed.editPrize || allowed.deletePrize) && (
                <td className="admin-table__actions">
                  {allowed.editPrize && (
                    <button type="button" className="button button--secondary button--small" onClick={() => onEdit(p.id)}>
                      修改
                    </button>
                  )}
                  {allowed.deletePrize && (
                    <button
                      type="button"
                      className="button button--danger button--small"
                      onClick={() => onDelete(p.id, p.name)}
                    >
                      刪除
                    </button>
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
