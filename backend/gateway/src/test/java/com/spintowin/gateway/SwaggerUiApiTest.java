package com.spintowin.gateway;

import com.spintowin.gateway.support.FakeUpstream;
import com.spintowin.gateway.support.GatewayTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gateway 上唯一的 Swagger UI：匯集三個服務的 OpenAPI 文件，以 Keycloak（Authorization Code + PKCE）授權後試打 API。
 * 文件與 UI 不需登入；文件請求轉送到各服務的 {@code /v3/api-docs}。
 */
class SwaggerUiApiTest extends GatewayTest {

    @Test
    void swaggerUiIsPublic() {
        client.get().uri("/swagger-ui.html").exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value("Location", location -> assertThat(location).isEqualTo("/swagger-ui/index.html"));
        client.get().uri("/swagger-ui/index.html").exchange()
                .expectStatus().isOk();
    }

    @Test
    void swaggerUiListsEveryServiceAndLogsInWithKeycloakPkce() {
        client.get().uri("/v3/api-docs/swagger-config").exchange()
                .expectStatus().isOk()
                .expectBody().json("""
                        {
                          "urls": [
                            {"name": "draw-service", "url": "/draw-service/v3/api-docs"},
                            {"name": "campaign-service", "url": "/campaign-service/v3/api-docs"},
                            {"name": "record-service", "url": "/record-service/v3/api-docs"}
                          ],
                          "persistAuthorization": true
                        }
                        """);

        client.get().uri("/swagger-ui/swagger-initializer.js").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(script -> assertThat(script)
                        .contains("ui.initOAuth(")
                        .contains("\"clientId\":\"spintowin-swagger\"")
                        .contains("\"usePkceWithAuthorizationCodeGrant\":true"));
    }

    @Test
    void serviceApiDocsAreRoutedWithoutAToken() {
        for (String service : new String[] {"campaign-service", "draw-service", "record-service"}) {
            client.get().uri("/" + service + "/v3/api-docs").exchange()
                    .expectStatus().isOk();
        }

        assertThat(UPSTREAM.received()).containsExactly(new FakeUpstream.ReceivedRequest("GET", "/v3/api-docs", null));
        assertThat(DRAW_UPSTREAM.received()).containsExactly(
                new FakeUpstream.ReceivedRequest("GET", "/v3/api-docs", null));
        assertThat(RECORD_UPSTREAM.received()).containsExactly(
                new FakeUpstream.ReceivedRequest("GET", "/v3/api-docs", null));
    }
}
