package com.spintowin.campaign.prize.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** ADMIN 新增 Prize：名稱、初始 Stock、Win Probability（百分比，最多 4 位小數）。 */
public record PrizeCreateRequest(
        @Schema(description = "獎品名稱（最多 100 字）", example = "免運券", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "初始 Stock（≥ 0）", example = "1000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @PositiveOrZero Integer stock,
        @Schema(description = "Win Probability：百分比 0–100，最多 4 位小數（0.0001 = 百萬分之一）", example = "30.5", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal winProbability) {
}
