package com.spintowin.draw.draw.model;

/**
 * 某位使用者在某個 Campaign 的 Draw Quota 使用狀況。
 *
 * @param drawQuota      Campaign 設定的每人 Draw Quota
 * @param usedDraws      已用的 Draw 次數
 * @param remainingDraws 剩餘的 Draw 次數（不小於 0）
 */
public record DrawQuotaStatus(int drawQuota, int usedDraws, int remainingDraws) {

    public static DrawQuotaStatus of(int drawQuota, int usedDraws) {
        return new DrawQuotaStatus(drawQuota, usedDraws, Math.max(drawQuota - usedDraws, 0));
    }
}
