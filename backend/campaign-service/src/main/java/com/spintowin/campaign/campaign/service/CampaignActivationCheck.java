package com.spintowin.campaign.campaign.service;

import com.spintowin.campaign.campaign.entity.Campaign;

/**
 * Campaign 上線（DRAFT → ACTIVE）前的前置條件，例如「至少一個 Prize」、「機率總和 ≤ 100%」。
 * <p>
 * 實作註冊為 Spring bean 即會在 {@link AdminCampaignService#activate} 中、Status 轉換之前依序執行
 * （可用 {@code @Order} 控制順序）；條件不成立時丟出
 * {@link com.spintowin.common.error.BusinessException}，整個上線動作即被拒絕、Status 不變。
 * Status 轉換本身是否合法（例如 ENDED 不能再上線）由 {@link Campaign#activate()} 負責，會先於這些檢查判斷。
 */
public interface CampaignActivationCheck {

    void check(Campaign campaign);
}
