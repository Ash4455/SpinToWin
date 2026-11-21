package com.spintowin.common.openapi;

import com.spintowin.common.error.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 依 {@link ApiErrors} 為每個 operation 產生錯誤回應：同一個 HTTP status 的錯誤碼合併成一個回應，
 * 說明列出每個錯誤碼的意義，並附上每個錯誤碼的 Problem Details 範例。
 */
class ProblemDetailsOperationCustomizer implements OperationCustomizer {

    static final String PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    /** 每個 API 都要 JWT 與角色。 */
    private static final List<ErrorCode> ALWAYS = List.of(ErrorCode.UNAUTHORIZED, ErrorCode.FORBIDDEN);

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        Set<ErrorCode> codes = new LinkedHashSet<>();
        ApiErrors declared = handlerMethod.getMethodAnnotation(ApiErrors.class);
        if (declared != null) {
            codes.addAll(Arrays.asList(declared.value()));
        }
        codes.addAll(ALWAYS);

        Map<Integer, List<ErrorCode>> byStatus = codes.stream().collect(Collectors.groupingBy(
                code -> code.status().value(), TreeMap::new, Collectors.toList()));

        ApiResponses responses = operation.getResponses() != null ? operation.getResponses() : new ApiResponses();
        byStatus.forEach((status, statusCodes) -> responses.addApiResponse(String.valueOf(status), problem(statusCodes)));
        operation.setResponses(responses);
        return operation;
    }

    private static ApiResponse problem(List<ErrorCode> codes) {
        String description = codes.stream()
                .map(code -> "- `" + code.name() + "`：" + code.description())
                .collect(Collectors.joining("\n"));

        Map<String, Example> examples = new LinkedHashMap<>();
        for (ErrorCode code : codes) {
            examples.put(code.name(), new Example().summary(code.name()).value(example(code)));
        }
        MediaType mediaType = new MediaType()
                .schema(new Schema<>().$ref(CommonOpenApiAutoConfiguration.PROBLEM_SCHEMA_REF))
                .examples(examples);
        return new ApiResponse().description(description).content(new Content().addMediaType(PROBLEM_JSON, mediaType));
    }

    private static Map<String, Object> example(ErrorCode code) {
        HttpStatus status = code.status();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "about:blank");
        value.put("title", status.getReasonPhrase());
        value.put("status", status.value());
        value.put("detail", code.description());
        value.put("instance", "/api/v1/...");
        value.put("code", code.name());
        if (code == ErrorCode.VALIDATION_FAILED) {
            value.put("errors", List.of(Map.of("field", "count", "message", "must be less than or equal to 10")));
        }
        return value;
    }
}
