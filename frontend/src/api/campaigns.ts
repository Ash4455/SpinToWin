import { api } from './http';

export type CampaignStatus = 'DRAFT' | 'ACTIVE' | 'ENDED';

/** 轉盤上的一個 Prize；winProbability 為百分比（最多 4 位小數）。 */
export interface CampaignPrize {
  id: number;
  name: string;
  winProbability: number;
}

/** GET /api/v1/campaigns/{id} 的回應（USER 看到的 Campaign）。 */
export interface Campaign {
  id: number;
  name: string;
  /** ISO-8601 UTC 時間 */
  startAt: string;
  endAt: string;
  drawQuota: number;
  status: CampaignStatus;
  prizes: CampaignPrize[];
  /** 銘謝惠顧機率（百分比）= 100 − 所有 Prize 機率總和 */
  noWinProbability: number;
}

export function getCampaign(id: number | string, signal?: AbortSignal): Promise<Campaign> {
  return api.get<Campaign>(`/campaigns/${encodeURIComponent(String(id))}`, { signal });
}
