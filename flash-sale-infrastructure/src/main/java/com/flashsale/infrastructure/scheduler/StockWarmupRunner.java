package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.StockWarmupUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 庫存預熱：啟動時執行一次，之後定期補跑。
 *
 * <p><b>為什麼啟動後還要定期補跑？</b>因為 Redis 鍵可能因為以下原因消失：
 * <ul>
 *   <li>Redis 重啟且未開啟持久化</li>
 *   <li>記憶體壓力觸發 eviction</li>
 *   <li>營運在活動開賣前才新建活動，錯過了啟動預熱</li>
 * </ul>
 * 定期補跑以 {@code force=false} 執行，只會補上缺失的鍵，
 * <b>不會覆蓋既有餘量</b>——這點至關重要，否則每次補跑都會把賣掉的庫存加回去。
 *
 * <p>啟動預熱失敗<b>不阻擋應用啟動</b>：Redis 暫時不可用時，
 * 讓應用起來並持續重試，遠比整個服務起不來要好。
 */
@Component
public class StockWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockWarmupRunner.class);

    private final StockWarmupUseCase warmupUseCase;

    public StockWarmupRunner(StockWarmupUseCase warmupUseCase) {
        this.warmupUseCase = warmupUseCase;
    }

    @Override
    public void run(ApplicationArguments args) {
        warmUpQuietly("啟動");
    }

    @Scheduled(fixedDelayString = "${flash-sale.stock.warmup-interval-ms:60000}", initialDelay = 60_000)
    public void periodicWarmUp() {
        warmUpQuietly("定期補跑");
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
