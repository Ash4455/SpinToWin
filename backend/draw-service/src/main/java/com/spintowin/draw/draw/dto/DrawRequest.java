package com.spintowin.draw.draw.dto;

import com.spintowin.draw.draw.service.DrawService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** {@code POST /campaigns/{id}/draws} 的 body：這次要抽幾次（1 = 單抽，2..10 = Multi-Draw）。 */
public record DrawRequest(
        @Schema(description = "這次要抽幾次：1 = 單抽，2..10 = Multi-Draw；不得超過剩餘次數", example = "1",
                minimum = "1", maximum = "10", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Min(1) @Max(DrawService.MAX_COUNT) Integer count) {
}
