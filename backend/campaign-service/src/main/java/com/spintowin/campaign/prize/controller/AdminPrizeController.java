package com.spintowin.campaign.prize.controller;

import com.spintowin.campaign.prize.dto.AdminPrizeResponse;
import com.spintowin.campaign.prize.dto.PrizeCreateRequest;
import com.spintowin.campaign.prize.dto.PrizeUpdateRequest;
import com.spintowin.campaign.prize.dto.StockAdjustmentRequest;
import com.spintowin.campaign.prize.service.AdminPrizeService;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/admin/campaigns/{campaignId}/prizes")
@Tag(name = "Admin: Prize", description = "ADMIN 管理活動的獎品。DRAFT 可新增、刪除；DRAFT 與 ACTIVE 可改名稱、機率與增減 Stock")
public class AdminPrizeController {

    private final AdminPrizeService adminPrizeService;

    public AdminPrizeController(AdminPrizeService adminPrizeService) {
        this.adminPrizeService = adminPrizeService;
    }

    @PostMapping
    @Operation(summary = "新增獎品", description = "只有 DRAFT 可以新增。所有 Prize 的 Win Probability 總和不得超過 100%。")
    @ApiResponse(responseCode = "201", description = "已建立；Location header 指向新獎品")
    @ApiErrors({ErrorCode.VALIDATION_FAILED, ErrorCode.CAMPAIGN_NOT_FOUND, ErrorCode.CAMPAIGN_NOT_EDITABLE,
            ErrorCode.PROBABILITY_SUM_EXCEEDED})
    public ResponseEntity<AdminPrizeResponse> create(
            @Parameter(description = "Campaign id", example = "1") @PathVariable Long campaignId,
            @Valid @RequestBody PrizeCreateRequest request) {
        AdminPrizeResponse created = adminPrizeService.create(campaignId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{prizeId}")
    @Operation(summary = "修改獎品名稱與機率",
            description = "DRAFT 與 ACTIVE 皆可，ACTIVE 時對之後的 Draw 立即生效。Stock 不在此覆寫，請用 `PATCH .../stock`。")
    @ApiErrors({ErrorCode.VALIDATION_FAILED, ErrorCode.CAMPAIGN_NOT_FOUND, ErrorCode.PRIZE_NOT_FOUND,
            ErrorCode.CAMPAIGN_NOT_EDITABLE, ErrorCode.PROBABILITY_SUM_EXCEEDED})
    public AdminPrizeResponse update(
            @Parameter(description = "Campaign id", example = "1") @PathVariable Long campaignId,
            @Parameter(description = "Prize id", example = "1") @PathVariable Long prizeId,
            @Valid @RequestBody PrizeUpdateRequest request) {
        return adminPrizeService.update(campaignId, prizeId, request);
    }

    @DeleteMapping("/{prizeId}")
    @Operation(summary = "刪除獎品", description = "只有 DRAFT 可以刪除。")
    @ApiResponse(responseCode = "204", description = "已刪除")
    @ApiErrors({ErrorCode.CAMPAIGN_NOT_FOUND, ErrorCode.PRIZE_NOT_FOUND, ErrorCode.CAMPAIGN_NOT_EDITABLE})
    public ResponseEntity<Void> delete(
            @Parameter(description = "Campaign id", example = "1") @PathVariable Long campaignId,
            @Parameter(description = "Prize id", example = "1") @PathVariable Long prizeId) {
        adminPrizeService.delete(campaignId, prizeId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{prizeId}/stock")
    @Operation(summary = "增減庫存",
            description = "以增減量調整（例如 +50、-10），總 Stock 與剩餘 Stock 同時增減，剩餘 Stock 不得變負數。"
                    + "ACTIVE 時以 Redis 的即時剩餘 Stock 判定，與抽獎扣減不互相覆蓋。")
    @ApiErrors({ErrorCode.VALIDATION_FAILED, ErrorCode.CAMPAIGN_NOT_FOUND, ErrorCode.PRIZE_NOT_FOUND,
            ErrorCode.CAMPAIGN_NOT_EDITABLE, ErrorCode.STOCK_NEGATIVE})
    public AdminPrizeResponse adjustStock(
            @Parameter(description = "Campaign id", example = "1") @PathVariable Long campaignId,
            @Parameter(description = "Prize id", example = "1") @PathVariable Long prizeId,
            @Valid @RequestBody StockAdjustmentRequest request) {
        return adminPrizeService.adjustStock(campaignId, prizeId, request);
    }
}
