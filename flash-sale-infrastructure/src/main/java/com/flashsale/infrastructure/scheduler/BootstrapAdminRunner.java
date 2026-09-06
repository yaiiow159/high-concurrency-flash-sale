package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.AdminBootstrapUseCase;
import com.flashsale.infrastructure.config.BootstrapAdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 開機時依設定建立初始管理員。
 *
 * <h2>失敗處理與其他 Runner 相反</h2>
 *
 * <p>{@code StockWarmupRunner} 失敗時不阻擋啟動——Redis 暫時不可用時，
 * 讓應用起來並持續重試遠比整個服務起不來要好。
 *
 * <p>這裡剛好相反：<b>設定錯了就讓啟動失敗</b>。理由是這個動作只發生一次，
 * 沒有下一輪可以重試；安靜略過的結果是操作者以為管理員建好了，
 * 而真相要到他打不開後台的那一刻才會揭曉——那時他已經在找別的原因了。
 *
 * <p>不設定則完全不啟用，那是預設行為，也不會有任何噪音。
 *
 * <h2>不需要跨節點互斥</h2>
 *
 * <p>兩個節點同時開機都通過「還沒有管理員」的檢查是可能的，
 * 但信箱的唯一索引會擋下第二個——而擋下這件事本身就是正確結果，
 * 不需要為它加一把鎖（CLAUDE.md 鐵則 6-1 的例外：
 * 已經有更便宜的互斥機制時就不必再加一層）。
 */
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
