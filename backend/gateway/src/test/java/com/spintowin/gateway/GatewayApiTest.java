package com.spintowin.gateway;

import com.spintowin.gateway.support.FakeUpstream;
import com.spintowin.gateway.support.GatewayTest;
import com.spintowin.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/** gateway 的 HTTP 邊界：JWT 驗證與 /api/v1 路由。 */
class GatewayApiTest extends GatewayTest {

    static final String USER_ID = "8f9c7a52-0000-4000-8000-000000000001";

    @Test
    void requestWithoutTokenIsRejectedWithProblemDetails() {
        client.get().uri("/api/v1/campaigns/1").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, v -> assertThat(v).startsWith("Bearer"))
                .expectBody().json("""
                        {"status": 401, "code": "UNAUTHORIZED", "instance": "/api/v1/campaigns/1"}
                        """);

        assertThat(UPSTREAM.received()).isEmpty();
    }

    @Test
    void tokenIssuedUnderPublicKeycloakUrlIsRoutedToCampaignServiceWithoutApiPrefix() {
        String token = TOKENS.accessToken(TestTokens.PUBLIC_ISSUER, USER_ID, "USER");

        client.get().uri("/api/v1/campaigns/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("""
                        {"servedBy": "upstream"}
                        """);

        assertThat(UPSTREAM.received()).containsExactly(
                new FakeUpstream.ReceivedRequest("GET", "/campaigns/1", "Bearer " + token));
    }

    @Test
    void adminCampaignPathsAreRoutedToCampaignService() {
        String token = TOKENS.accessToken(TestTokens.PUBLIC_ISSUER, USER_ID, "ADMIN", "USER");

        client.post().uri("/api/v1/admin/campaigns/1/activate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        assertThat(UPSTREAM.received()).extracting(FakeUpstream.ReceivedRequest::path)
                .containsExactly("/admin/campaigns/1/activate");
    }

    @Test
    void drawAndMyQuotaPathsAreRoutedToDrawServiceWithoutApiPrefix() {
        String token = TOKENS.accessToken(TestTokens.PUBLIC_ISSUER, USER_ID, "USER");

        client.post().uri("/api/v1/campaigns/1/draws")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"count\": 1}")
                .exchange()
                .expectStatus().isOk();
        client.get().uri("/api/v1/campaigns/1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        assertThat(DRAW_UPSTREAM.received()).containsExactly(
                new FakeUpstream.ReceivedRequest("POST", "/campaigns/1/draws", "Bearer " + token),
                new FakeUpstream.ReceivedRequest("GET", "/campaigns/1/me", "Bearer " + token));
        assertThat(UPSTREAM.received()).isEmpty();
    }

    @Test
    void myDrawRecordsAndAdminStatsAreRoutedToRecordServiceWithoutApiPrefix() {
        String token = TOKENS.accessToken(TestTokens.PUBLIC_ISSUER, USER_ID, "ADMIN", "USER");

        for (String path : new String[] {"/api/v1/campaigns/1/draws/me?page=1&size=5",
                "/api/v1/admin/campaigns/1/stats"}) {
            client.get().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk();
        }

        assertThat(RECORD_UPSTREAM.received()).containsExactly(
                new FakeUpstream.ReceivedRequest("GET", "/campaigns/1/draws/me", "Bearer " + token),
                new FakeUpstream.ReceivedRequest("GET", "/admin/campaigns/1/stats", "Bearer " + token));
        assertThat(UPSTREAM.received()).isEmpty();
        assertThat(DRAW_UPSTREAM.received()).isEmpty();
    }

    @Test
    void otherCampaignPathsStayOnCampaignService() {
        String token = TOKENS.accessToken(TestTokens.PUBLIC_ISSUER, USER_ID, "USER");

        for (String path : new String[] {"/api/v1/campaigns/1", "/api/v1/campaigns/1/draws/me/x",
                "/api/v1/campaigns/1/me/x", "/api/v1/admin/campaigns/1/draws", "/api/v1/admin/campaigns/1/stats/x"}) {
            client.get().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk();
        }

        assertThat(UPSTREAM.received()).extracting(FakeUpstream.ReceivedRequest::path).containsExactly(
                "/campaigns/1", "/campaigns/1/draws/me/x", "/campaigns/1/me/x", "/admin/campaigns/1/draws",
                "/admin/campaigns/1/stats/x");
        assertThat(DRAW_UPSTREAM.received()).isEmpty();
        assertThat(RECORD_UPSTREAM.received()).isEmpty();
    }

    @Test
    void servicesAreToldTheStrippedApiPrefixSoTheirUrlsMatchWhatTheClientCalled() {
        String token = TOKENS.accessToken(TestTokens.PUBLIC_ISSUER, USER_ID, "USER");

        client.get().uri("/api/v1/campaigns/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Forwarded-Prefix", "/spoofed")
                .exchange()
                .expectStatus().isOk();

        assertThat(UPSTREAM.receivedHeader("X-Forwarded-Prefix")).containsExactly("/api/v1");
    }

    @Test
    void tokenWhoseIssuerIsNotThePublicKeycloakUrlIsRejected() {
        String token = TOKENS.accessToken("http://keycloak:8080/realms/spintowin", USER_ID, "USER");

        client.get().uri("/api/v1/campaigns/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
                        v -> assertThat(v).startsWith("Bearer error=\"invalid_token\""))
                .expectBody().json("""
                        {"status": 401, "code": "UNAUTHORIZED", "instance": "/api/v1/campaigns/1"}
                        """);

        assertThat(UPSTREAM.received()).isEmpty();
    }

    @Test
    void healthIsPublic() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().json("""
                        {"status": "UP"}
                        """);
    }
}
