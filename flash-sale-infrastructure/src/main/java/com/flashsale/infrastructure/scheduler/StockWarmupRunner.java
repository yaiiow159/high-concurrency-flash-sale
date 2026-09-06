package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.StockWarmupUseCase;
import com.flashsale.application.port.out.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 庫存預熱：啟動時執行一次，之後定期補跑。 */
@Component
public class StockWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockWarmupRunner.class);

    private static final String LOCK_KEY = "seckill:lock:stock-warmup";

    /** 掃全表 + 逐活動預熱；租期抓得比一輪的預期耗時寬鬆。 */
    private static final Duration LOCK_LEASE = Duration.ofMinutes(2);

    private final StockWarmupUseCase warmupUseCase;
    private final DistributedLock distributedLock;

    public StockWarmupRunner(StockWarmupUseCase warmupUseCase, DistributedLock distributedLock) {
        this.warmupUseCase = warmupUseCase;
        this.distributedLock = distributedLock;
    }

    @Override
    public void run(ApplicationArguments args) {
        warmUpQuietly("啟動");
    }

    @Scheduled(fixedDelayString = "${flash-sale.stock.warmup-interval-ms:60000}", initialDelay = 60_000)
    public void periodicWarmUp() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, () -> warmUpQuietly("定期補跑"));
    }

    private void warmUpQuietly(String trigger) {
        try {
            int warmed = warmupUseCase.warmUpAllOnline();
            log.info("[{}] 庫存預熱完成，共 {} 個活動", trigger, warmed);
        } catch (RuntimeException e) {
            log.error("[{}] 庫存預熱失敗，將於下一輪重試", trigger, e);
        }
    }
}
