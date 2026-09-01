package com.flashsale.infrastructure.adapter.out.identity;

import com.flashsale.application.port.out.AccessTokenIssuer;
import com.flashsale.domain.identity.User;
import com.flashsale.infrastructure.config.JwtProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 以 HMAC 簽發 access token。
 *
 * <p><b>claim 只放驗證與授權必需的資訊</b>：{@code sub}（userId）與 {@code scope}。
 * 不放信箱、姓名這類個資——JWT 只是 Base64 編碼，任何拿到令牌的人都能讀出內容，
 * 而令牌會出現在瀏覽器記憶體、代理日誌、錯誤回報等許多地方。
 *
 * <p>這也呼應 ADR-0005 推導出的鐵則：令牌能待在熱路徑上，
 * 是因為驗證不需要遠端呼叫；因此需要的資訊必須全在 claim 裡，
 * 但也僅限於「必要」的那些。
 */
@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtProperties properties;
    private final Clock clock;

    public JwtAccessTokenIssuer(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedAccessToken issue(User user) {
        Instant now = clock.instant();
        Duration ttl = properties.accessTokenTtl();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(user.id()))
                .issuer(properties.issuer())
                .audience(properties.audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                // scope 由角色推導，權威定義在 UserRole 這個領域列舉裡
                .claim("scope", user.role().scopeClaim())
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(properties.secret().getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException e) {
            // 簽章失敗代表金鑰設定有問題（多半是長度不足），屬於啟動期就該發現的設定錯誤。
            throw new IllegalStateException("簽發 access token 失敗，請檢查 JWT 金鑰設定", e);
        }
        return new IssuedAccessToken(jwt.serialize(), ttl);
    }
}
