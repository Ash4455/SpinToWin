package com.spintowin.campaign.campaign.controller;

import com.spintowin.campaign.campaign.dto.AdminCampaignResponse;
import com.spintowin.campaign.campaign.dto.CampaignRequest;
import com.spintowin.campaign.campaign.service.AdminCampaignService;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/admin/campaigns")
@Tag(name = "Admin: Campaign", description = "ADMIN 管理活動：建立、修改（僅 DRAFT）、上線、結束與 Redis live state 重建")
public class AdminCampaignController {

    private final AdminCampaignService adminCampaignService;

    public AdminCampaignController(AdminCampaignService adminCampaignService) {
        this.adminCampaignService = adminCampaignService;
    }

    @PostMapping
    @Operation(summary = "建立活動（DRAFT）",
            description = "新活動的 Campaign Status 為 DRAFT，尚無 Prize。`endAt` 必須晚於 `startAt`（UTC）。")
    @ApiResponse(responseCode = "201", description = "已建立；Location header 指向新活動")
    @ApiErrors(ErrorCode.VALIDATION_FAILED)
    public ResponseEntity<AdminCampaignResponse> create(@Valid @RequestBody CampaignRequest request) {
        AdminCampaignResponse created = adminCampaignService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    @Operation(summary = "列出所有活動", description = "所有 Campaign（依 id 排序，不分頁），含 Prize、剩餘 Stock 與銘謝惠顧機率。")
    public List<AdminCampaignResponse> list() {
        return adminCampaignService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查看活動", description = "Campaign 設定、Campaign Status、Prize（依 id 排序）與銘謝惠顧機率。")
    @ApiErrors(ErrorCode.CAMPAIGN_NOT_FOUND)
    public AdminCampaignResponse get(@Parameter(description = "Campaign id", example = "1") @PathVariable Long id) {
        return adminCampaignService.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改活動設定", description = "整筆覆寫名稱、時間窗與 Draw Quota。只有 DRAFT 可以修改。")
    @ApiErrors({ErrorCode.VALIDATION_FAILED, ErrorCode.CAMPAIGN_NOT_FOUND, ErrorCode.CAMPAIGN_NOT_EDITABLE})
    public AdminCampaignResponse update(@Parameter(description = "Campaign id", example = "1") @PathVariable Long id,
                                        @Valid @RequestBody CampaignRequest request) {
        return adminCampaignService.update(id, request);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "上線（DRAFT → ACTIVE）",
            description = "上線條件：至少一個 Prize、Win Probability 總和 ≤ 100%。上線時把活動載入 Redis，之後即可抽獎。")
    @ApiErrors({ErrorCode.CAMPAIGN_NOT_FOUND, ErrorCode.INVALID_STATUS_TRANSITION, ErrorCode.CAMPAIGN_HAS_NO_PRIZE,
            ErrorCode.PROBABILITY_SUM_EXCEEDED})
    public AdminCampaignResponse activate(@Parameter(description = "Campaign id", example = "1") @PathVariable Long id) {
        return adminCampaignService.activate(id);
    }

    @PostMapping("/{id}/end")
    @Operation(summary = "結束（DRAFT / ACTIVE → ENDED）",
            description = "結束後不能再抽獎；仍可查詢紀錄與統計。DRAFT 可直接結束（放棄尚未上線的活動）。")
    @ApiErrors({ErrorCode.CAMPAIGN_NOT_FOUND, ErrorCode.INVALID_STATUS_TRANSITION})
    public AdminCampaignResponse end(@Parameter(description = "Campaign id", example = "1") @PathVariable Long id) {
        return adminCampaignService.end(id);
    }

    /** 從 MySQL 重建 ACTIVE Campaign 在 Redis 中的 live state（Redis 資料遺失後使用）。 */
    @PostMapping("/{id}/redis/rebuild")
    @Operation(summary = "從 MySQL 重建 Redis live state",
            description = "Redis 資料遺失後使用：以 MySQL 的設定與 remaining_stock 覆寫 Redis 中的活動資料（不動已用次數）。"
                    + "只用於 ACTIVE 活動；若在 Redis 正常時使用，尚未被消費的抽獎事件所扣的 Stock 會被加回。")
    @ApiErrors({ErrorCode.CAMPAIGN_NOT_FOUND, ErrorCode.CAMPAIGN_NOT_ACTIVE})
    public AdminCampaignResponse rebuildLiveState(
            @Parameter(description = "Campaign id", example = "1") @PathVariable Long id) {
        return adminCampaignService.rebuildLiveState(id);
    }
}
