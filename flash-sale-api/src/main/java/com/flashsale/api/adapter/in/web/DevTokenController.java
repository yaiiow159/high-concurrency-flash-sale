package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.config.SecurityProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 開發用發證端點。
 *
 * <p><b>這個類別絕不能出現在正式環境。</b> 它沒有任何身分驗證——
 * 任何人都能指定 userId 換到令牌，等同完全沒有認證。
 *
 * <p>存在的理由是讓這個專案 clone 下來就能跑：沒有它就得先架一套 IdP 才能發出第一個請求。
 * 防護採三層：
 * <ol>
 *   <li>{@code @ConditionalOnProperty} 預設 {@code false}——不明確開啟就<b>整個 Bean 都不會註冊</b>，
 *       端點根本不存在，而不是「存在但擋住」</li>
 *   <li>啟動時印出醒目警告，讓誤開的人在日誌裡看得到</li>
 *   <li>設定值來自環境變數，正式部署只要不帶那個變數就是關閉</li>
 * </ol>
 *
 * <p>接上正式 IdP 的做法見 ADR-0005。
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "flash-sale.security.dev-token", name = "enabled", havingValue = "true")
@Tag(name = "開發用認證", description = "僅限本機開發，正式環境必須關閉")
public class DevTokenController {

    private static final Logger log = LoggerFactory.getLogger(DevTokenController.class);

    private final SecurityProperties properties;
    private final Clock clock;

    public DevTokenController(SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    void warnLoudly() {
        log.warn("""

                ============================================================
                  開發用發證端點已啟用：POST /api/v1/auth/dev-token
                  任何人都能取得任意使用者的令牌，等同沒有認證。
                  正式環境請設定 flash-sale.security.dev-token.enabled=false
                ============================================================""");
    }

    @PostMapping("/dev-token")
    @Operation(summary = "取得開發用令牌", description = "僅限本機開發使用，不做任何身分驗證")
    public ApiResponse<TokenResponse> issue(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "false") boolean admin) throws JOSEException {

        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.jwt().ttl());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                // sub 使用標準 claim 承載 userId，換成外部 IdP 時不需對方配合加欄位
                .subject(String.valueOf(userId))
                .issuer(properties.jwt().issuer())
                .audience(properties.jwt().audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .claim("scope", admin ? "seckill:order seckill:admin" : "seckill:order")
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(properties.jwt().secret().getBytes(StandardCharsets.UTF_8)));

        return ApiResponse.ok(new TokenResponse(
                jwt.serialize(), "Bearer", properties.jwt().ttl().toSeconds(), List.of("seckill:order")));
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, List<String> scopes) {
    }
}
