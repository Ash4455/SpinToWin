package com.spintowin.campaign.campaign.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** ADMIN 建立或修改 Campaign 的設定：名稱、時間窗（UTC）、Draw Quota。 */
@ValidTimeWindow
public record CampaignRequest(
        @Schema(description = "活動名稱（最多 100 字）", example = "週年慶轉盤抽獎", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "開始時間（UTC，ISO-8601）", example = "2026-12-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Instant startAt,
        @Schema(description = "結束時間（UTC），須晚於 startAt", example = "2026-12-31T23:59:59Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Instant endAt,
        @Schema(description = "每位使用者可抽的總次數（> 0）", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Positive Integer drawQuota) {
}
