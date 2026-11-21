package com.spintowin.gateway;

import com.spintowin.gateway.support.GatewayTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽獎 API 的每使用者限流（Redis RequestRateLimiter，key = JWT subject）。
 * 正式值是每秒 5 次（replenishRate 5、burstCapacity 5）；這裡把回補放慢到每 10 秒 1 次（每次請求花 10 個 token，
 * 每秒補 1 個，桶子 50 個），連打 5 次以內必定成功、第 6 次必定被擋，不受測試機器快慢影響。
 */
@TestPropertySource(properties = {
        "DRAW_RATE_LIMIT_REPLENISH_RATE=1",
        "DRAW_RATE_LIMIT_BURST_CAPACITY=50",
        "DRAW_RATE_LIMIT_REQUESTED_TOKENS=10"})
class DrawRateLimitApiTest extends GatewayTest {

    static final int BURST = 5;

    @Test
    void drawRequestsBeyondTheBurstAreRejectedWithRateLimitedProblemDetails() {
        String token = userToken(newUserId());
        for (int i = 0; i < BURST; i++) {
            draw(token).expectStatus().isOk();
        }

        draw(token)
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody().json("""
                        {"status": 429, "code": "RATE_LIMITED", "instance": "/api/v1/campaigns/1/draws"}
                        """);

        assertThat(DRAW_UPSTREAM.received()).hasSize(BURST);
    }

    @Test
    void limitIsPerUserSoAnotherUserCanStillDraw() {
        String alice = userToken(newUserId());
        for (int i = 0; i < BURST; i++) {
            draw(alice).expectStatus().isOk();
        }
        draw(alice).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        draw(userToken(newUserId())).expectStatus().isOk();
    }

    @Test
    void onlyTheDrawPostIsLimited() {
        String token = userToken(newUserId());
        for (int i = 0; i < BURST; i++) {
            draw(token).expectStatus().isOk();
        }
        draw(token).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        for (String path : new String[] {"/api/v1/campaigns/1/me", "/api/v1/campaigns/1/draws/me",
                "/api/v1/campaigns/1"}) {
            client.get().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk();
        }
    }

    private WebTestClient.ResponseSpec draw(String token) {
        return client.post().uri("/api/v1/campaigns/1/draws")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", newUserId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"count\": 1}")
                .exchange();
    }
}
