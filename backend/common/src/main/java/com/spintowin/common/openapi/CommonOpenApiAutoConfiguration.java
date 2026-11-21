package com.spintowin.common.openapi;

import com.spintowin.common.error.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;

/**
 * 有 springdoc 的 servlet 服務共用的 OpenAPI 設定：
 * <ul>
 *   <li>server = gateway 的 /api/v1（文件中的路徑是服務內路徑，gateway 會去掉 /api/v1 再轉送）</li>
 *   <li>Keycloak OAuth2 Authorization Code 授權（Swagger UI 以 PKCE 登入），套用到所有 operation</li>
 *   <li>{@code Problem} schema 與 {@link ApiErrors} 產生的錯誤回應</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OperationCustomizer.class)
@EnableConfigurationProperties(OpenApiProperties.class)
public class CommonOpenApiAutoConfiguration {

    public static final String SECURITY_SCHEME = "keycloak";
    static final String PROBLEM_SCHEMA = "Problem";
    static final String PROBLEM_SCHEMA_REF = "#/components/schemas/" + PROBLEM_SCHEMA;

    @Bean
    @ConditionalOnMissingBean
    OpenAPI openApi(OpenApiProperties properties, Environment environment) {
        String title = properties.title() != null ? properties.title()
                : environment.getProperty("spring.application.name", "SpinToWin API");
        String issuer = properties.issuerUri() != null ? properties.issuerUri()
                : environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");

        OpenAPI openApi = new OpenAPI()
                .info(new Info().title(title).description(properties.description()).version("v1"))
                .servers(List.of(new Server().url(properties.serverUrl()).description("經 gateway（/api/v1）")))
                .components(new Components().addSchemas(PROBLEM_SCHEMA, problemSchema()));
        if (issuer != null) {
            openApi.addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
            openApi.getComponents().addSecuritySchemes(SECURITY_SCHEME, keycloak(issuer));
        }
        return openApi;
    }

    @Bean
    OperationCustomizer problemDetailsOperationCustomizer() {
        return new ProblemDetailsOperationCustomizer();
    }

    private static SecurityScheme keycloak(String issuer) {
        String base = issuer.replaceAll("/+$", "") + "/protocol/openid-connect";
        return new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("Keycloak 登入（Authorization Code + PKCE）。測試帳號：user1/user1（USER）、admin/admin（ADMIN + USER）。")
                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                        .authorizationUrl(base + "/auth")
                        .tokenUrl(base + "/token")
                        .scopes(new Scopes().addString("openid", "OpenID Connect"))));
    }

    /** RFC 9457 Problem Details 加上本專案的 {@code code} 與 {@code errors[]}。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<?> problemSchema() {
        Schema fieldError = new ObjectSchema()
                .addProperty("field", new StringSchema().description("欄位、參數或 header 名稱").example("count"))
                .addProperty("message", new StringSchema().description("錯誤訊息").example("must be less than or equal to 10"));
        List<String> codes = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();
        return new ObjectSchema()
                .description("RFC 9457 Problem Details（application/problem+json）")
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Conflict"))
                .addProperty("status", new IntegerSchema().example(409))
                .addProperty("detail", new StringSchema().description("給人看的說明"))
                .addProperty("instance", new StringSchema().description("發生錯誤的請求路徑").example("/api/v1/campaigns/1/draws"))
                .addProperty("code", new StringSchema()._enum(codes).description("機器可讀的錯誤碼"))
                .addProperty("errors", new ArraySchema().items(fieldError).description("只有 VALIDATION_FAILED 會帶"))
                .required(List.of("status", "code"));
    }
}
