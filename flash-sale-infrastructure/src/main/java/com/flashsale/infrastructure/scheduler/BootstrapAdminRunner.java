package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.AdminBootstrapUseCase;
import com.flashsale.infrastructure.config.BootstrapAdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 開機時依設定建立初始管理員。 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final AdminBootstrapUseCase adminBootstrap;
    private final BootstrapAdminProperties properties;

    public BootstrapAdminRunner(AdminBootstrapUseCase adminBootstrap,
                                BootstrapAdminProperties properties) {
        this.adminBootstrap = adminBootstrap;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            return;
        }
        if (properties.isIncomplete()) {
            throw new IllegalStateException(
                    "設定了 flash-sale.security.bootstrap-admin.email 卻沒有給 password。"
                            + "初始管理員刻意沒有預設密碼——請以環境變數注入，例如 "
                            + "BOOTSTRAP_ADMIN_PASSWORD。");
        }

        AdminBootstrapUseCase.Outcome outcome = adminBootstrap.bootstrap(
                properties.email(), properties.password(), properties.displayNameOrDefault());
        log.info("初始管理員設定結果：{}", outcome);
    }
}
