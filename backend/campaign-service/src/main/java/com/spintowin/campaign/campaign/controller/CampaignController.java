package com.spintowin.campaign.campaign.controller;

import com.spintowin.campaign.campaign.dto.CampaignResponse;
import com.spintowin.campaign.campaign.service.CampaignService;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/campaigns")
@Tag(name = "Campaign", description = "USER 查看活動資訊（轉盤用）")
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "活動資訊與獎品、機率",
            description = "Campaign 的時間窗、Draw Quota、Campaign Status，以及依 Prize 順序排列的 Prize 名稱與 Win Probability"
                    + "（百分比，最多 4 位小數）；`noWinProbability` 是銘謝惠顧的機率。"
                    + "DRAFT（尚未上線）的活動對使用者而言不存在，與不存在的 id 一樣回 404 `CAMPAIGN_NOT_FOUND`。")
    @ApiErrors(ErrorCode.CAMPAIGN_NOT_FOUND)
    public CampaignResponse getCampaign(@Parameter(description = "Campaign id", example = "1") @PathVariable Long id) {
        return campaignService.getCampaign(id);
    }
}
