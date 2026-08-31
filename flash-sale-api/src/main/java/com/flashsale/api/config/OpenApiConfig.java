package com.flashsale.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文件設定。
 *
 * <p>宣告 bearer 認證方案後，Swagger UI 會出現 Authorize 按鈕，
 * 貼上令牌即可直接試打受保護的端點——少了這一步，文件頁上所有寫入操作都只會回 401，
 * 讀文件的人會以為 API 壞了。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI flashSaleOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("高併發秒殺系統 API")
                        .version("1.0.0")
                        .description("""
                                搶購為非同步流程：庫存預扣成功即回 202 與訂單號，
                                訂單由 MQ 消費端建立，前端以訂單號輪詢結果。

                                取得開發用令牌（僅本機啟用）：
                                POST /api/v1/auth/dev-token?userId=1001
                                """))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("將 dev-token 端點取得的 accessToken 貼在此處")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
