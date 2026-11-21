package com.spintowin.draw.draw.controller;

import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.error.FieldViolation;
import com.spintowin.common.error.Problems;
import com.spintowin.common.openapi.ApiErrors;
import com.spintowin.common.redis.IdempotencyScope;
import com.spintowin.draw.draw.dto.DrawRequest;
import com.spintowin.draw.draw.dto.DrawResponse;
import com.spintowin.draw.draw.dto.MyDrawQuotaResponse;
import com.spintowin.draw.draw.service.DrawService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** USER 的抽獎 API。使用者 id 一律取自 JWT 的 subject。 */
@RestController
@RequestMapping("/campaigns/{campaignId}")
@Tag(name = "Draw", description = "抽獎與剩餘次數（USER）。使用者一律是 access token 的 subject。")
public class DrawController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    private final DrawService drawService;

    public DrawController(DrawService drawService) {
        this.drawService = drawService;
    }

    /**
     * 單抽或 Multi-Draw。{@code Idempotency-Key} 必填：網路重試沿用同一個 key 會得到相同結果，不會重複扣次數。
     */
    @PostMapping("/draws")
    @Operation(summary = "抽獎（單抽或 Multi-Draw）",
            description = """
                    在 Redis 以單一 Lua script 原子判定：Campaign 須為 ACTIVE 且在時間窗內、剩餘次數 ≥ count，
                    否則整筆拒絕且不扣任何東西。每一抽依 Win Probability 落點決定 Prize，Stock 用完改判銘謝惠顧。
                    結果依抽獎順序回傳；`won=false` 為銘謝惠顧（prizeId、prizeName 為 null）。

                    **冪等**：`Idempotency-Key` 必填（建議每次抽獎產生新的 UUID）。網路重試沿用同一個 key，
                    會得到完全相同的回應，不會重複扣次數（24 小時內有效）。

                    **限流**：gateway 對每位使用者每秒最多 5 次，超過回 429 `RATE_LIMITED`。""")
    @ApiErrors({ErrorCode.VALIDATION_FAILED, ErrorCode.IDEMPOTENCY_KEY_REQUIRED, ErrorCode.QUOTA_EXCEEDED,
            ErrorCode.CAMPAIGN_NOT_ACTIVE, ErrorCode.RATE_LIMITED})
    public DrawResponse draw(@Parameter(description = "Campaign id", example = "1") @PathVariable long campaignId,
                             @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = true,
                                     description = "這次抽獎請求的唯一識別（最多 128 字元）；重試時沿用同一個值",
                                     example = "3f0c2a8e-6d1b-4a8e-9a57-0d6f1c2b9e41")
                             @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String idempotencyKey,
                             @Valid @RequestBody DrawRequest request,
                             @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "The " + IDEMPOTENCY_KEY + " header is required.");
        }
        if (idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            // 手動檢查而不是在參數上加 @Size：參數 constraint 會讓整個方法走 method validation，body 的欄位錯誤就變成 request.count
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST, Problems.validationFailed(List.of(
                    new FieldViolation(IDEMPOTENCY_KEY, "size must be at most " + IDEMPOTENCY_KEY_MAX_LENGTH))), null);
        }
        return DrawResponse.from(drawService.draw(
                new IdempotencyScope(campaignId, jwt.getSubject(), idempotencyKey), request.count()));
    }

    /** 目前使用者的剩餘次數。 */
    @GetMapping("/me")
    @Operation(summary = "我的剩餘 Draw 次數",
            description = "目前使用者在此 Campaign 的 Draw Quota、已用與剩餘次數（即時，取自 Redis）。Campaign 結束後仍可查詢。")
    @ApiErrors(ErrorCode.CAMPAIGN_NOT_ACTIVE)
    public MyDrawQuotaResponse me(@Parameter(description = "Campaign id", example = "1") @PathVariable long campaignId,
                                  @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return MyDrawQuotaResponse.from(campaignId, drawService.quota(campaignId, jwt.getSubject()));
    }
}
