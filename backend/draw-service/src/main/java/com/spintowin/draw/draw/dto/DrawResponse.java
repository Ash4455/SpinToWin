package com.spintowin.draw.draw.dto;

import com.spintowin.draw.draw.model.DrawRequestResult;
import com.spintowin.draw.draw.model.DrawResult;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 抽獎回應：依抽獎順序排列的 Draw Result 與判定後的剩餘次數。以相同 Idempotency-Key 重送時內容完全相同。
 */
public record DrawResponse(
        @Schema(description = "這次抽獎請求的 id（每個 drawId 以它為前綴）") String requestId,
        @Schema(description = "判定時間（UTC）") Instant drawnAt,
        @Schema(description = "扣除這次之後的剩餘 Draw 次數", example = "9") int remainingDraws,
        @Schema(description = "依抽獎順序排列的 Draw Result（長度 = count）") List<Result> results) {

    public static DrawResponse from(DrawRequestResult result) {
        return new DrawResponse(result.requestId(), result.drawnAt(), result.remainingDraws(),
                result.results().stream().map(Result::from).toList());
    }

    /** 一次 Draw 的結果；銘謝惠顧時 {@code won=false}，{@code prizeId} 與 {@code prizeName} 為 null。 */
    public record Result(
            @Schema(description = "單次 Draw 的 id（{requestId}-{1 起算的序號}）") String drawId,
            @Schema(description = "抽中的 Prize id；銘謝惠顧為 null", example = "3") @Nullable Long prizeId,
            @Schema(description = "抽中的 Prize 名稱；銘謝惠顧為 null", example = "免運券") @Nullable String prizeName,
            @Schema(description = "是否中獎（false = 銘謝惠顧）") boolean won) {

        static Result from(DrawResult result) {
            return new Result(result.drawId(), result.prizeId(), result.prizeName(), result.won());
        }
    }
}
