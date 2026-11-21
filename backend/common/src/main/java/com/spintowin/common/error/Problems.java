package com.spintowin.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.util.List;

/**
 * 建立本專案統一格式的 RFC 9457 Problem Details：標準欄位加上自訂 {@code code}，
 * 欄位驗證錯誤另外帶 {@code errors[]}（{@link FieldViolation}）。
 */
public final class Problems {

    /** 自訂欄位：機器可讀錯誤碼。 */
    public static final String CODE = "code";

    /** 自訂欄位：欄位錯誤清單。 */
    public static final String ERRORS = "errors";

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setProperty(CODE, code.name());
        return problem;
    }

    public static ProblemDetail validationFailed(List<FieldViolation> violations) {
        ProblemDetail problem = of(ErrorCode.VALIDATION_FAILED, "Request has invalid fields.");
        problem.setProperty(ERRORS, violations);
        return problem;
    }

    /** 為框架產生、尚未帶 {@code code} 的 Problem Details 補上錯誤碼。 */
    public static void ensureCode(ProblemDetail problem) {
        if (problem.getProperties() == null || !problem.getProperties().containsKey(CODE)) {
            problem.setProperty(CODE, codeFor(HttpStatusCode.valueOf(problem.getStatus())));
        }
    }

    /**
     * 沒有專屬錯誤碼的框架錯誤：400 / 401 / 403 / 5xx 對應通用錯誤碼，
     * 其餘以 HTTP status 名稱代替（例如 {@code NOT_FOUND}、{@code METHOD_NOT_ALLOWED}）。
     */
    static String codeFor(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ErrorCode.INTERNAL_ERROR.name();
        }
        return switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED.name();
            case 401 -> ErrorCode.UNAUTHORIZED.name();
            case 403 -> ErrorCode.FORBIDDEN.name();
            default -> {
                HttpStatus resolved = HttpStatus.resolve(status.value());
                yield resolved != null ? resolved.name() : String.valueOf(status.value());
            }
        };
    }
}
