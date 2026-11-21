import { api } from './http';

/** 我的一筆 Draw Result 紀錄（record-service）。 */
export interface DrawRecord {
  drawId: string;
  requestId: string;
  prizeId: number | null;
  prizeName: string | null;
  won: boolean;
  /** ISO-8601 UTC 時間 */
  drawnAt: string;
}

export interface DrawRecordPage {
  content: DrawRecord[];
  /** 從 0 開始 */
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** GET /api/v1/campaigns/{id}/draws/me：新到舊、分頁。 */
export function getMyDrawRecords(
  campaignId: number | string,
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<DrawRecordPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return api.get<DrawRecordPage>(`/campaigns/${encodeURIComponent(String(campaignId))}/draws/me?${query}`, {
    signal,
  });
}
