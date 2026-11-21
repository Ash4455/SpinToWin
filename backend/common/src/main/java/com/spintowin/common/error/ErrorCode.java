package com.spintowin.common.error;

import org.springframework.http.HttpStatus;

/**
 * 機器可讀的錯誤碼，放在 Problem Details 的 {@code code} 欄位。
 * 新增錯誤碼時一併指定對應的 HTTP status 與說明（說明會出現在 OpenAPI 文件的錯誤回應中）。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "請求的欄位或參數不合法，errors[] 列出每個欄位的錯誤。"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未帶 access token，或 token 無效、過期。"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "token 有效，但沒有這個 API 需要的角色（USER 或 ADMIN）。"),
    CAMPAIGN_NOT_FOUND(HttpStatus.NOT_FOUND, "Campaign 不存在。"),
    /** 不允許的 Campaign Status 轉換，例如 ENDED → ACTIVE。 */
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "不允許的 Campaign Status 轉換（只能 DRAFT → ACTIVE、DRAFT / ACTIVE → ENDED）。"),
    /** Campaign 目前的 Status 不允許修改其設定（只有 DRAFT 可改）。 */
    CAMPAIGN_NOT_EDITABLE(HttpStatus.CONFLICT, "Campaign 目前的 Status 不允許這項修改。"),
    /** Prize 不存在，或不屬於路徑上的 Campaign。 */
    PRIZE_NOT_FOUND(HttpStatus.NOT_FOUND, "Prize 不存在，或不屬於路徑上的 Campaign。"),
    /** 新增或修改後，Campaign 所有 Prize 的 Win Probability 總和會超過 100%。 */
    PROBABILITY_SUM_EXCEEDED(HttpStatus.CONFLICT, "修改後所有 Prize 的 Win Probability 總和會超過 100%。"),
    /** Stock 增減量會讓剩餘 Stock 變成負數。 */
    STOCK_NEGATIVE(HttpStatus.CONFLICT, "Stock 增減量會讓剩餘 Stock 變成負數。"),
    /** Campaign 沒有任何 Prize，不能上線。 */
    CAMPAIGN_HAS_NO_PRIZE(HttpStatus.CONFLICT, "Campaign 沒有任何 Prize，不能上線。"),
    /** 操作需要 Campaign 為 ACTIVE（例如重建 Redis live state、抽獎），但它不是。 */
    CAMPAIGN_NOT_ACTIVE(HttpStatus.CONFLICT, "Campaign 不是 ACTIVE、尚未開始或已結束。"),
    /** 剩餘 Draw 次數不足這次請求的 count（整筆拒絕，不扣任何東西）。 */
    QUOTA_EXCEEDED(HttpStatus.CONFLICT, "剩餘 Draw 次數不足這次的 count，整筆拒絕且不扣任何東西。"),
    /** 抽獎請求缺少 {@code Idempotency-Key} header。 */
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "缺少 Idempotency-Key header。"),
    /** 同一使用者呼叫抽獎 API 太快（gateway 的每使用者限流）。 */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "抽獎太快：gateway 對每位使用者限流（每秒 5 次），請稍後再試。"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "非預期的伺服器錯誤。");

    private final HttpStatus status;
    private final String description;

    ErrorCode(HttpStatus status, String description) {
        this.status = status;
        this.description = description;
    }

    public HttpStatus status() {
        return status;
    }

    /** 給 API 使用者看的說明（OpenAPI 文件用）；回應的 {@code detail} 另由丟出錯誤的地方決定。 */
    public String description() {
        return description;
    }
}
