import type { CampaignStatus } from '../../api/campaigns';

/** 管理後台在某個 Campaign Status 下可以做的操作；與後端規則一致，後端仍會再檢查。 */
export interface CampaignActions {
  /** 修改名稱、時間窗、Draw Quota（只有 DRAFT） */
  editCampaign: boolean;
  /** DRAFT → ACTIVE */
  activate: boolean;
  /** DRAFT／ACTIVE → ENDED */
  end: boolean;
  /** 新增、刪除 Prize（只有 DRAFT） */
  addPrize: boolean;
  deletePrize: boolean;
  /** 修改 Prize 名稱與 Win Probability、Stock 增減（DRAFT、ACTIVE） */
  editPrize: boolean;
  adjustStock: boolean;
  /** 從 MySQL 重建 Redis 中的 live state（只有 ACTIVE） */
  rebuildLiveState: boolean;
}

export function campaignActions(status: CampaignStatus): CampaignActions {
  const draft = status === 'DRAFT';
  const active = status === 'ACTIVE';
  return {
    editCampaign: draft,
    activate: draft,
    end: draft || active,
    addPrize: draft,
    deletePrize: draft,
    editPrize: draft || active,
    adjustStock: draft || active,
    rebuildLiveState: active,
  };
}
