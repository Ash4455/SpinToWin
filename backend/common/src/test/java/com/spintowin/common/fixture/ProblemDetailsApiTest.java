package com.spintowin.common.fixture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * common 的錯誤格式契約：任何 servlet 服務加入 common 後，錯誤都是帶 {@code code} 的 RFC 9457 Problem Details。
 * security 由各服務自行測試，這裡關掉 filter 只看 MVC 層的錯誤處理。
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class ProblemDetailsApiTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void invalidRequestBodyListsEveryFieldError() {
        var response = mvc.post().uri("/fixture/prizes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "name": " ", "stock": -1 }
                        """)
                .exchange();

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 400,
                  "code": "VALIDATION_FAILED",
                  "instance": "/fixture/prizes",
                  "errors": [ { "field": "name" }, { "field": "stock" } ]
                }
                """);
    }

    @Test
    void invalidRequestParameterIsFieldError() {
        var response = mvc.get().uri("/fixture/draws?count=0").exchange();

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "code": "VALIDATION_FAILED",
                  "errors": [ { "field": "count" } ]
                }
                """);
    }

    @Test
    void unexpectedErrorIsInternalErrorWithoutLeakingDetails() {
        var response = mvc.get().uri("/fixture/boom").exchange();

        assertThat(response).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": 500, "code": "INTERNAL_ERROR", "instance": "/fixture/boom" }
                """);
        assertThat(response).bodyText().doesNotContain("hunter2");
    }

    @Test
    void frameworkErrorWithoutDedicatedCodeUsesStatusName() {
        var response = mvc.delete().uri("/fixture/prizes").exchange();

        assertThat(response).hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": 405, "code": "METHOD_NOT_ALLOWED", "instance": "/fixture/prizes" }
                """);
    }
}
