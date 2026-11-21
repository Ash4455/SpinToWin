package com.spintowin.common.fixture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * common 的 OpenAPI 契約：servlet 服務加入 common 與 springdoc 後，{@code /v3/api-docs} 自動帶有
 * gateway 的 /api/v1 server、Keycloak（Authorization Code + PKCE）授權設定，以及每個錯誤回應的 Problem Details 與錯誤碼。
 */
@SpringBootTest(properties = {
        "spring.application.name=fixture-service",
        "spintowin.openapi.description=Fixture endpoints.",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080/realms/spintowin"})
@AutoConfigureMockMvc(addFilters = false)
class OpenApiContractTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void specIsServedForTheGatewayPrefixAndNamedAfterTheService() {
        var response = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(response).hasStatus(HttpStatus.OK);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "info": {"title": "fixture-service", "description": "Fixture endpoints."},
                  "servers": [{"url": "/api/v1"}]
                }
                """);
    }

    @Test
    void everyOperationCanBeAuthorizedThroughKeycloak() {
        var response = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "security": [{"keycloak": []}],
                  "components": {"securitySchemes": {"keycloak": {
                    "type": "oauth2",
                    "flows": {"authorizationCode": {
                      "authorizationUrl": "http://localhost:8080/realms/spintowin/protocol/openid-connect/auth",
                      "tokenUrl": "http://localhost:8080/realms/spintowin/protocol/openid-connect/token"
                    }}
                  }}}
                }
                """);
    }

    @Test
    void declaredErrorCodesAreDocumentedAsProblemDetailsGroupedByStatus() {
        var response = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(response).bodyJson()
                .extractingPath("$.paths['/fixture/draws'].get.responses").asMap()
                .containsKeys("200", "400", "401", "403", "404");
        assertThat(response).bodyJson()
                .extractingPath("$.paths['/fixture/draws'].get.responses['404'].content['application/problem+json']")
                .asMap().containsKey("schema");
        assertThat(response).bodyJson()
                .extractingPath("$.paths['/fixture/draws'].get.responses['404'].content['application/problem+json']"
                        + ".examples.CAMPAIGN_NOT_FOUND.value")
                .asMap().containsEntry("status", 404).containsEntry("code", "CAMPAIGN_NOT_FOUND");
        assertThat(response).bodyJson()
                .extractingPath("$.paths['/fixture/draws'].get.responses['400'].description").asString()
                .contains("VALIDATION_FAILED");
        assertThat(response).bodyJson()
                .extractingPath("$.paths['/fixture/draws'].get.responses['401'].description").asString()
                .contains("UNAUTHORIZED");
    }

    @Test
    void problemDetailsSchemaListsTheCustomFields() {
        var response = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(response).bodyJson()
                .extractingPath("$.components.schemas.Problem.properties").asMap()
                .containsKeys("type", "title", "status", "detail", "instance", "code", "errors");
    }
}
