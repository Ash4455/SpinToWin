package com.spintowin.common.openapi;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 各服務 OpenAPI 文件的設定（{@code spintowin.openapi.*}）。
 *
 * @param title       文件標題；未設定時用 {@code spring.application.name}
 * @param description 文件說明（markdown）
 * @param serverUrl   文件中 API 的 base URL。路徑是服務內的路徑（例如 /campaigns/{id}），經 gateway 時要加 /api/v1；
 *                    用相對 URL，Swagger UI 會以文件所在的 gateway 位址解析
 * @param issuerUri   Keycloak realm 的對外位址（瀏覽器可連）；未設定時用
 *                    {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
 */
@ConfigurationProperties("spintowin.openapi")
public record OpenApiProperties(
        @Nullable String title,
        @Nullable String description,
        @DefaultValue("/api/v1") String serverUrl,
        @Nullable String issuerUri) {
}
