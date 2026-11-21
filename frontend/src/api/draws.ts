import { api } from './http';

/** 一次 Draw 的結果；銘謝惠顧時 won=false、prizeId／prizeName 為 null。 */
export interface DrawResult {
  drawId: string;
  prizeId: number | null;
  prizeName: string | null;
  won: boolean;
}

/** POST /api/v1/campaigns/{id}/draws 的回應；results 依抽獎順序排列。 */
export interface DrawResponse {
  requestId: string;
  /** ISO-8601 UTC 時間 */
  drawnAt: string;
  remainingDraws: number;
  results: DrawResult[];
}

/** GET /api/v1/campaigns/{id}/me：目前使用者的 Draw Quota 使用狀況。 */
export interface MyDrawQuota {
  campaignId: number;
  drawQuota: number;
  usedDraws: number;
  remainingDraws: number;
}

/** 單抽（count = 1）或 Multi-Draw（2..10）。同一個 idempotencyKey 重送會拿到同一份結果、不會重複扣次。 */
export function postDraws(
  campaignId: number | string,
  count: number,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<DrawResponse> {
  return api.post<DrawResponse>(
    `/campaigns/${encodeURIComponent(String(campaignId))}/draws`,
    { count },
    { headers: { 'Idempotency-Key': idempotencyKey }, signal },
  );
}

export function getMyDrawQuota(campaignId: number | string, signal?: AbortSignal): Promise<MyDrawQuota> {
  return api.get<MyDrawQuota>(`/campaigns/${encodeURIComponent(String(campaignId))}/me`, { signal });
}
