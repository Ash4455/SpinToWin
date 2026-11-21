import type { CampaignStatus } from './campaigns';
import { api } from './http';

/** ADMIN 看到的 Prize；winProbability 為百分比（最多 4 位小數）。 */
export interface AdminPrize {
  id: number;
  name: string;
  winProbability: number;
  totalStock: number;
  /** 剩餘 Stock（MySQL；ACTIVE 時會稍微落後 Redis 中的即時值） */
  remainingStock: number;
}

/** GET/POST/PUT /api/v1/admin/campaigns[/{id}] 的回應。 */
export interface AdminCampaign {
  id: number;
  name: string;
  /** ISO-8601 UTC 時間 */
  startAt: string;
  endAt: string;
  drawQuota: number;
  status: CampaignStatus;
  /** 依 Prize 順序（id 遞增） */
  prizes: AdminPrize[];
  /** 銘謝惠顧機率（百分比） */
  noWinProbability: number;
}

/** 建立與修改 Campaign 共用；未填的欄位送 null，由後端回欄位錯誤。 */
export interface CampaignRequest {
  name: string;
  startAt: string | null;
  endAt: string | null;
  drawQuota: number | null;
}

export interface PrizeCreateRequest {
  name: string;
  stock: number | null;
  /** 百分比，最多 4 位小數（由 ppm 換算，JSON 會是 "0.0001" 這樣的精確字面值） */
  winProbability: number;
}

export interface PrizeUpdateRequest {
  name: string;
  winProbability: number;
}

/** GET /api/v1/admin/campaigns/{id}/stats：只列出至少被抽出一次的 Prize。 */
export interface CampaignStats {
  campaignId: number;
  totalDraws: number;
  noWinCount: number;
  prizes: { prizeId: number; prizeName: string; drawnCount: number }[];
}

const base = '/admin/campaigns';
const campaignPath = (id: number | string) => `${base}/${encodeURIComponent(String(id))}`;
const prizePath = (campaignId: number | string, prizeId: number) => `${campaignPath(campaignId)}/prizes/${prizeId}`;

export const adminCampaigns = {
  list: (signal?: AbortSignal) => api.get<AdminCampaign[]>(base, { signal }),
  get: (id: number | string, signal?: AbortSignal) => api.get<AdminCampaign>(campaignPath(id), { signal }),
  create: (body: CampaignRequest) => api.post<AdminCampaign>(base, body),
  update: (id: number | string, body: CampaignRequest) => api.put<AdminCampaign>(campaignPath(id), body),
  activate: (id: number | string) => api.post<AdminCampaign>(`${campaignPath(id)}/activate`),
  end: (id: number | string) => api.post<AdminCampaign>(`${campaignPath(id)}/end`),
  /** 從 MySQL 重建 Redis 中的 live state（只在 Redis 遺失資料後使用） */
  rebuildLiveState: (id: number | string) => api.post<AdminCampaign>(`${campaignPath(id)}/redis/rebuild`),
  stats: (id: number | string, signal?: AbortSignal) => api.get<CampaignStats>(`${campaignPath(id)}/stats`, { signal }),

  addPrize: (campaignId: number | string, body: PrizeCreateRequest) =>
    api.post<AdminPrize>(`${campaignPath(campaignId)}/prizes`, body),
  updatePrize: (campaignId: number | string, prizeId: number, body: PrizeUpdateRequest) =>
    api.put<AdminPrize>(prizePath(campaignId, prizeId), body),
  deletePrize: (campaignId: number | string, prizeId: number) => api.delete(prizePath(campaignId, prizeId)),
  adjustStock: (campaignId: number | string, prizeId: number, delta: number) =>
    api.patch<AdminPrize>(`${prizePath(campaignId, prizeId)}/stock`, { delta }),
};
