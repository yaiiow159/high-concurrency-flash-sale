package com.flashsale.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 用預設金鑰起服務時大聲警告。任何讀過原始碼的人都能用它自己簽發資格憑證，
 * 而偽造的憑證驗簽成功後看起來就是正常流量——風控歸零且不會出現在任何指標上。
 * 與 jwt.secret、payment.callback-secret 同一類問題；改成拒絕啟動是下一步。
 */
@Component
public class RiskSecretGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RiskSecretGuard.class);
    static final String DEFAULT_SECRET = "dev-only-risk-secret-change-me-0123456789abcdef";

    private final RiskProperties properties;

    public RiskSecretGuard(RiskProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (DEFAULT_SECRET.equals(properties.secret())) {
            log.warn("""

                    ============================================================
                      flash-sale.risk.secret 仍是預設值。
                      任何人都能自己簽發搶購資格，風控形同不存在。
                      正式環境請以 RISK_SECRET 覆寫。
                    ============================================================""");
        }
    }
}
