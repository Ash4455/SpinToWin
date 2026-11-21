package com.spintowin.campaign.prize.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Stock 增減量，例如 +50 或 -10；結果不得為負數。 */
public record StockAdjustmentRequest(
        @Schema(description = "Stock 增減量：正數增加、負數減少；剩餘 Stock 不得變負數", example = "50", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Integer delta) {
}
