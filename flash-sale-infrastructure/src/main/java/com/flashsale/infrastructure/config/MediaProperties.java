package com.flashsale.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 物件儲存設定（ADR-0027）。
 *
 * @param publicBaseUrl 對外的讀取網址前綴。與 endpoint 分開是因為
 *                      正式環境前面會擺 CDN——寫入走儲存端點，
 *                      讀取走 CDN，兩者不是同一個網域
 * @param uploadTtl     預簽名 URL 的有效秒數。短一點：這個 URL 等於
 *                      一張寫入許可，外流之後在有效期內都能被拿去用
 */
@ConfigurationProperties(prefix = "flash-sale.media")
public record MediaProperties(

        @DefaultValue("http://localhost:9000") String endpoint,
        @DefaultValue("http://localhost:9000/product-media") String publicBaseUrl,
        @DefaultValue("product-media") String bucket,
        @DefaultValue("minioadmin") String accessKey,
        @DefaultValue("minioadmin") String secretKey,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("300") long uploadTtlSeconds,
        /** 孤兒物件的寬限期（小時）。必須明顯長於任何進行中的上傳流程。 */
        @DefaultValue("24") long orphanGraceHours
) {
}
