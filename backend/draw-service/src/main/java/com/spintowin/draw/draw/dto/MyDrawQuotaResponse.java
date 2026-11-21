package com.spintowin.draw.draw.dto;

import com.spintowin.draw.draw.model.DrawQuotaStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** {@code GET /campaigns/{id}/me}：目前使用者在此 Campaign 的 Draw Quota 使用狀況。 */
public record MyDrawQuotaResponse(
        @Schema(example = "1") long campaignId,
        @Schema(description = "每位使用者在此 Campaign 可抽的總次數", example = "10") int drawQuota,
        @Schema(description = "已用次數", example = "3") int usedDraws,
        @Schema(description = "剩餘次數", example = "7") int remainingDraws) {

    public static MyDrawQuotaResponse from(long campaignId, DrawQuotaStatus status) {
        return new MyDrawQuotaResponse(campaignId, status.drawQuota(), status.usedDraws(), status.remainingDraws());
    }
}
