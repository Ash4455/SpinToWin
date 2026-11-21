package com.spintowin.record.record.controller;

import com.spintowin.record.record.dto.DrawRecordPageResponse;
import com.spintowin.record.record.service.DrawRecordService;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 使用者查自己的抽獎紀錄。紀錄由 draw.completed 非同步寫入，剛抽完的結果可能晚一點才出現。 */
@RestController
@Tag(name = "Draw record", description = "USER 查自己的抽獎紀錄")
public class MyDrawRecordController {

    private final DrawRecordService drawRecordService;

    public MyDrawRecordController(DrawRecordService drawRecordService) {
        this.drawRecordService = drawRecordService;
    }

    @GetMapping("/campaigns/{campaignId}/draws/me")
    @Operation(summary = "我的抽獎紀錄（分頁，新到舊）",
            description = """
                    目前使用者在此 Campaign 的每一次 Draw Result，依抽獎時間由新到舊（同一次 Multi-Draw 內最後一抽在前）。
                    紀錄經 Kafka 非同步寫入，剛抽完的結果通常 1 秒內出現；畫面可先顯示抽獎回應，再以 drawId 去重。""")
    @ApiErrors(ErrorCode.VALIDATION_FAILED)
    public DrawRecordPageResponse myDraws(
            @Parameter(description = "Campaign id", example = "1") @PathVariable long campaignId,
            @Parameter(description = "頁碼，從 0 開始", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "每頁筆數，1–100", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return drawRecordService.findMine(campaignId, jwt.getSubject(), page, size);
    }
}
