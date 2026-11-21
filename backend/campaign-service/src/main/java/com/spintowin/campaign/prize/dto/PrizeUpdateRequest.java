package com.spintowin.campaign.prize.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * ADMIN 修改 Prize 的名稱與 Win Probability（DRAFT、ACTIVE 皆可）。
 * Stock 不在此覆寫，只能以 {@code PATCH .../stock} 的增減量調整，避免與抽獎扣減競爭時遺失已抽出的數量。
 */
public record PrizeUpdateRequest(
        @Schema(description = "獎品名稱（最多 100 字）", example = "免運券", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "Win Probability：百分比 0–100，最多 4 位小數", example = "30.5", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal winProbability) {
}
