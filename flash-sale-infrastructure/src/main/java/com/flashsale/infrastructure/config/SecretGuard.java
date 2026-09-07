package com.flashsale.infrastructure.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用預設金鑰起服務時**拒絕啟動**。
 *
 * <p>這三把金鑰的預設值就寫在版控裡，任何讀過原始碼的人都能拿它偽造已登入的身分、
 * 偽造付款成功的回調、或自己簽發搶購資格——而偽造出來的請求驗簽成功後，
 * 看起來就是正常流量，不會出現在任何指標上。
 *
 * <p>先前只是啟動時印一行警告。警告在正式部署的日誌洪流裡等於不存在，
 * 而這是「漏判會安靜到出事才發現」的那一類問題，因此改成 fail-closed
 * （與 {@code SnowflakeNodeIdGuard} 同一個立場）。
 *
 * <p>本機開發加上 {@code dev} profile 即可放行。方向刻意是這一邊：
 * 忘了加 profile 只是本機起不來，一改就好；反過來讓正式環境靜靜用著預設金鑰，
 * 代價是整套認證與風控形同不存在。
 */
@Component
public class SecretGuard {

    static final String DEV_PROFILE = "dev";

    static final String DEFAULT_JWT_SECRET = "dev-only-secret-change-me-in-production-0123456789abcdef";
    static final String DEFAULT_PAYMENT_SECRET = "dev-only-payment-secret-change-me-0123456789abcdef";
    static final String DEFAULT_RISK_SECRET = "dev-only-risk-secret-change-me-0123456789abcdef";

    public SecretGuard(Environment environment, JwtProperties jwt,
                       PaymentProperties payment, RiskProperties risk) {
        Map<String, String> offenders = new LinkedHashMap<>();
        check(offenders, "flash-sale.security.jwt.secret", jwt.secret(), DEFAULT_JWT_SECRET,
                "JWT_SECRET", "任何人都能簽發通過驗證的存取權杖，等於全站免登入");
        check(offenders, "flash-sale.payment.callback-secret", payment.callbackSecret(),
                DEFAULT_PAYMENT_SECRET, "PAYMENT_CALLBACK_SECRET", "任何人都能偽造「付款成功」的回調");
        check(offenders, "flash-sale.risk.secret", risk.secret(), DEFAULT_RISK_SECRET,
                "RISK_SECRET", "任何人都能自己簽發搶購資格，風控形同不存在");

        if (offenders.isEmpty() || environment.matchesProfiles(DEV_PROFILE)) {
            return;
        }
        throw new IllegalStateException(("""

                ============================================================
                  以下金鑰仍是版控裡的預設值，拒絕啟動：

                %s
                  正式環境請以環境變數覆寫；本機開發請加上 dev profile：
                    mvn spring-boot:run -pl flash-sale-api -Dspring-boot.run.profiles=dev
                ============================================================""")
                .formatted(String.join("\n", offenders.values())));
    }

    private static void check(Map<String, String> offenders, String key, String actual,
                              String defaultValue, String envVar, String consequence) {
        if (defaultValue.equals(actual)) {
            offenders.put(key, "    %s（%s）%n      以 %s 覆寫".formatted(key, consequence, envVar));
        }
    }
}
