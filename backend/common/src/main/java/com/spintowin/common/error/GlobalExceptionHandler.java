package com.spintowin.common.error;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 全域例外處理：所有錯誤一律回 {@code application/problem+json}，並帶 {@code code} 與 {@code instance}。
 * <ul>
 *   <li>{@link BusinessException} → 其 {@link ErrorCode} 與 status</li>
 *   <li>Bean Validation 與型別轉換錯誤 → 400 {@code VALIDATION_FAILED} 加 {@code errors[]}</li>
 *   <li>其他 Spring MVC 例外 → 框架的 status，{@code code} 見 {@link Problems#ensureCode}</li>
 *   <li>未預期例外 → 500 {@code INTERNAL_ERROR}，不洩漏內部訊息</li>
 * </ul>
 * 由 {@link com.spintowin.common.web.CommonWebAutoConfiguration} 自動註冊到 servlet 服務。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException ex, WebRequest request) {
        ProblemDetail problem = Problems.of(ex.getErrorCode(), ex.getMessage());
        return createResponseEntity(problem, HttpHeaders.EMPTY, ex.getErrorCode().status(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error", ex);
        ProblemDetail problem = Problems.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
        return createResponseEntity(problem, HttpHeaders.EMPTY, ErrorCode.INTERNAL_ERROR.status(), request);
    }

    /** {@code @Valid @RequestBody} 驗證失敗。 */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> violations.add(new FieldViolation(e.getField(), e.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> violations.add(new FieldViolation(e.getObjectName(), e.getDefaultMessage())));
        return validationFailed(violations, headers, request);
    }

    /** 方法參數（{@code @PathVariable}、{@code @RequestParam} 等）上的 constraint 驗證失敗。 */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError
                        ? parameter + "." + fieldError.getField()
                        : parameter;
                violations.add(new FieldViolation(field, error.getDefaultMessage()));
            }
        });
        return validationFailed(violations, headers, request);
    }

    /** 參數型別轉換失敗，例如 {@code /campaigns/abc}。 */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "value";
        FieldViolation violation = new FieldViolation(ex.getPropertyName(), "must be a valid " + requiredType);
        return validationFailed(List.of(violation), headers, request);
    }

    /** 所有回應的出口：補上 {@code code} 與 {@code instance}。 */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            Problems.ensureCode(problem);
            if (problem.getInstance() == null && request instanceof ServletWebRequest servletRequest) {
                problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationFailed(
            List<FieldViolation> violations, HttpHeaders headers, WebRequest request) {
        return createResponseEntity(
                Problems.validationFailed(violations), headers, ErrorCode.VALIDATION_FAILED.status(), request);
    }
}
