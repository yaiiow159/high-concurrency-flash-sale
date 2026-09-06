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

/** 認證與授權設定。 */
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

    /** 不需登入即可呼叫的認證端點。 */
    private static final String[] PUBLIC_AUTH_ENDPOINTS = {
            "/api/v1/auth/register", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/logout"
    };

    /** 金流回調端點。 */
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
                        // 庫存維運：對帳會揭露完整帳務、釋放會實際改動庫存，
                        // 兩者都不是一般使用者該碰的。整段路徑一律要 admin scope，
                        // 這樣之後往這個前綴新增端點時不會漏掉授權。
                        .requestMatchers("/api/v1/admin/**").hasAuthority(SCOPE_ADMIN)
                        // 商品頁要能匿名瀏覽，但只開放 GET。
                        // 這也是這些端點能被 CDN 快取的前提——
                        // 帶 Authorization 的請求無法共用快取。
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/activities", "/api/v1/activities/**",
                                "/api/v1/catalog/**",
                                // 首頁版型不含任何身分資料，是它能被 ISR 與 CDN 快取的前提
                                "/api/v1/home",
                                // sitemap：爬蟲要讀得到，本來就是公開資料
                                "/api/v1/catalog/sitemap",
                                // 搜尋不帶身分也不改狀態，而且是使用者進站的第一個動作。
                                // 要求登入才能搜尋等於把人擋在門外
                                "/api/v1/search/products",
                                // 搜尋建議與搜尋同一個理由：它是使用者還沒登入
                                // 就會用到的東西。逐一列出而不是 /search/**——
                                // 那會連還沒寫的端點也一起開放
                                "/api/v1/search/suggestions",
                                // 評價的讀取必須公開——評價存在的意義就是幫
                                // 「還沒買、也還沒登入」的人做決定。
                                // 逐一列出而不是 /reviews/**：那會連 /reviews/mine
                                // 也一起開放，而那是「我寫過哪些評價」，屬於個人資料
                                "/api/v1/catalog/products/ratings",
                                "/api/v1/catalog/products/*/rating",
                                "/api/v1/catalog/products/*/reviews")
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

    /** 對稱金鑰（HS256）解碼器。 */
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

    /** 受眾驗證。 */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(expectedAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "令牌的 %s 不包含 %s".formatted(JwtClaimNames.AUD, expectedAudience),
                        null));
    }
}
