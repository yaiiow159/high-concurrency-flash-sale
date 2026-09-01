package com.flashsale.api.config;

import com.flashsale.api.adapter.in.web.security.ApiAccessDeniedHandler;
import com.flashsale.api.adapter.in.web.security.ApiAuthenticationEntryPoint;
import com.flashsale.infrastructure.config.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 認證與授權設定。
 *
 * <p><b>為什麼是無狀態 JWT 而不是 Session？</b>
 * 秒殺服務要水平擴展到數十個節點，Session 需要黏著或集中儲存——
 * 前者破壞負載平衡，後者等於在熱路徑上多一次 Redis 往返。
 * JWT 驗證是純 CPU 運算（HMAC 約數十微秒），不需要任何遠端呼叫，
 * 這是它能待在熱路徑上的唯一理由。
 *
 * <p><b>絕不可為了取得使用者資料而在此查資料庫</b>——那會讓每個請求多一次 DB 往返，
 * 直接摧毀削峰設計。需要的資訊（userId、權限）必須全部放在令牌的 claim 裡。
 */
@Configuration
@EnableWebSecurity
// 自己宣告所需的設定，而不倚賴別的 @Configuration 剛好也註冊了它。
// 少了這行，@WebMvcTest 切片會因為找不到 JwtProperties 而整組起不來——
// 這正是切片測試的價值：它會逼出「只在完整 context 下才成立」的隱性依賴。
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** 管理端操作所需的權限。Spring Security 會把 scope claim 轉為 {@code SCOPE_} 前綴的權限。 */
    public static final String SCOPE_ADMIN = "SCOPE_seckill:admin";

    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"
    };

    /**
     * 不需登入即可呼叫的認證端點。
     *
     * <p>刻意逐一列出，<b>不用 {@code /api/v1/auth/**} 一次放行</b>——
     * 那會連 {@code /me} 也一起開放，而它必須要有身分才有意義。
     * 日後新增 {@code /auth} 底下的端點時，預設是受保護的，要開放得明確加進來。
     */
    private static final String[] PUBLIC_AUTH_ENDPOINTS = {
            "/api/v1/auth/register", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/logout"
    };

    /**
     * 金流回調端點。
     *
     * <p>必須匿名——金流閘道不會帶著使用者的令牌打過來。
     * 因此它的安全性<b>完全</b>建立在簽章驗證上，
     * 見 {@code PaymentApplicationService.handleGatewayCallback}。
     */
    private static final String PAYMENT_CALLBACK_ENDPOINT = "/api/v1/payments/callback";

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                // 純 API 服務，憑證放在 Authorization 標頭而非 cookie，
                // 瀏覽器不會自動攜帶，CSRF 攻擊的前提不成立。
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, PAYMENT_CALLBACK_ENDPOINT).permitAll()
                        // 順序關鍵：預熱是管理操作，必須排在下面的活動查詢放行規則之前，
                        // 否則會被 /api/v1/activities/** 的規則先攔截。
                        .requestMatchers(HttpMethod.POST, "/api/v1/activities/*/warm-up")
                        .hasAuthority(SCOPE_ADMIN)
                        // 商品頁要能匿名瀏覽，但只開放 GET。
                        // 這也是這些端點能被 CDN 快取的前提——
                        // 帶 Authorization 的請求無法共用快取。
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/activities", "/api/v1/activities/**",
                                "/api/v1/catalog/**")
                        .permitAll()
                        // 其餘一律需要認證。用 anyRequest() 收尾而非逐條列舉，
                        // 新增端點時預設是「受保護」而非「開放」——安全的預設值。
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    /**
     * 對稱金鑰（HS256）解碼器。
     *
     * <p><b>接上正式 IdP 的做法</b>：設定 {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}
     * 並刪掉這個 Bean，Spring Boot 會自動改用非對稱驗證。
     * 對稱金鑰的缺點是「驗證方也能簽發」——每個持有金鑰的服務都能偽造令牌，
     * 服務數量一多就必須換成非對稱。此處採用它僅為了讓本專案不必額外架 IdP。
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        SecretKeySpec key = new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                // 預設驗證器負責 exp / nbf
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                audienceValidator(properties.audience())));
        return decoder;
    }

    /**
     * 受眾驗證。
     *
     * <p>這一項最常被省略，但少了它，同一個 IdP 簽發給<b>其他服務</b>的令牌
     * 也能拿來存取本服務——使用者在別的系統拿到的合法令牌，就成了這裡的萬用鑰匙。
     */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(expectedAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "令牌的 %s 不包含 %s".formatted(JwtClaimNames.AUD, expectedAudience),
                        null));
    }
}
