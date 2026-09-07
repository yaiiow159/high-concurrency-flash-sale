package com.flashsale.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 預設金鑰的守衛。 */
@DisplayName("金鑰守衛")
class SecretGuardTest {

    private static final String REAL = "a-real-secret-0123456789abcdef0123456789";

    private static JwtProperties jwt(String secret) {
        return new JwtProperties(secret, "flash-sale", "flash-sale-api",
                Duration.ofMinutes(15), Duration.ofDays(7));
    }

    private static PaymentProperties payment(String secret) {
        return new PaymentProperties(secret, "/pay/simulated", Duration.ofSeconds(2), true);
    }

    private static RiskProperties risk(String secret) {
        return new RiskProperties(secret, true, Duration.ofMinutes(15), Duration.ofMinutes(30),
                Duration.ofMinutes(3), Duration.ofMinutes(10), 600L, 20, 3, 5, 60);
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    @DisplayName("預設金鑰 + 非 dev：拒絕啟動")
    void refusesToStartWithDefaultSecrets() {
        assertThatThrownBy(() -> new SecretGuard(env(),
                jwt(SecretGuard.DEFAULT_JWT_SECRET),
                payment(SecretGuard.DEFAULT_PAYMENT_SECRET),
                risk(SecretGuard.DEFAULT_RISK_SECRET)))
                .isInstanceOf(IllegalStateException.class)
                // 三把都要列出來，只講第一把會讓人改完一個又撞一次
                .hasMessageContaining("flash-sale.security.jwt.secret")
                .hasMessageContaining("flash-sale.payment.callback-secret")
                .hasMessageContaining("flash-sale.risk.secret");
    }

    @Test
    @DisplayName("只要有一把是預設值就擋——三把的後果都是單獨成立的")
    void refusesWhenAnySingleSecretIsDefault() {
        assertThatThrownBy(() -> new SecretGuard(env("prod"),
                jwt(REAL), payment(REAL), risk(SecretGuard.DEFAULT_RISK_SECRET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flash-sale.risk.secret")
                .hasMessageNotContaining("flash-sale.security.jwt.secret");
    }

    @Test
    @DisplayName("dev profile 放行：忘了加 profile 只是本機起不來，一改就好")
    void allowsDefaultsUnderDevProfile() {
        assertThatCode(() -> new SecretGuard(env(SecretGuard.DEV_PROFILE),
                jwt(SecretGuard.DEFAULT_JWT_SECRET),
                payment(SecretGuard.DEFAULT_PAYMENT_SECRET),
                risk(SecretGuard.DEFAULT_RISK_SECRET)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("金鑰都被覆寫過就放行，與 profile 無關")
    void allowsOverriddenSecrets() {
        assertThatCode(() -> new SecretGuard(env("prod"), jwt(REAL), payment(REAL), risk(REAL)))
                .doesNotThrowAnyException();
    }
}
