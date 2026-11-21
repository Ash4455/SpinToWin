package com.spintowin.record.stats.controller;

import com.spintowin.record.stats.dto.CampaignStatsResponse;
import com.spintowin.record.stats.service.CampaignStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN 看 Campaign 的抽獎統計。不認得的 campaignId 回傳全為 0 的統計（record-service 不擁有 Campaign）。 */
@RestController
@Tag(name = "Admin: Stats", description = "ADMIN 看活動的抽獎統計")
public class AdminCampaignStatsController {

    private final CampaignStatsService campaignStatsService;

    public AdminCampaignStatsController(CampaignStatsService campaignStatsService) {
        this.campaignStatsService = campaignStatsService;
    }

    @GetMapping("/admin/campaigns/{campaignId}/stats")
    @Operation(summary = "活動抽獎統計",
            description = "總抽獎次數、銘謝惠顧次數與各 Prize 抽出數（只列至少抽出一次的 Prize，依 prizeId 排序）。"
                    + "不認得的 campaignId 回傳全為 0 的統計。資料經 Kafka 非同步寫入，與抽獎有些微延遲。")
    public CampaignStatsResponse stats(
            @Parameter(description = "Campaign id", example = "1") @PathVariable long campaignId) {
        return campaignStatsService.stats(campaignId);
    }
}
