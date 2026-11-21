package com.spintowin.common.error;

/**
 * 可預期的業務錯誤。由 {@link GlobalExceptionHandler} 轉成 Problem Details：
 * status 取自 {@link ErrorCode#status()}，{@code detail} 為給人看的說明。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
