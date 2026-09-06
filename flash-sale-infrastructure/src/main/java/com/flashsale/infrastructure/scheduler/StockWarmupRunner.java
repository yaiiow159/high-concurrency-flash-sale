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
 *
 * <h2>定期補跑要跨節點互斥，啟動預熱不要</h2>
 *
 * <p><b>補跑加鎖</b>，但要清楚它買到的是什麼：鎖在動作結束就釋放，
 * 所以這是<b>不讓兩個節點同時跑</b>，<b>不是</b>「整個叢集每輪只跑一次」——
 * 各節點的計時器仍然各跑各的，只是不會撞在一起。
 *
 * <p>預熱本身有冪等保護（劃撥流水擋重複劃撥、{@code initialize} 遇到既有鍵就略過），
 * 因此這不是正確性修補。加它的理由是消掉並行執行這個變數：
 * 「兩個節點同時算出餘量再同時寫入」不需要每次都重新推理一遍是否安全。
 *
 * <p><b>啟動不加鎖</b>，理由相反：剛起來的節點必須確認庫存鍵真的在，
 * 那是它能開始接流量的前提。此時因為別的節點正持鎖而略過，
 * 等於把自己的就緒條件交給另一個節點負責。
 * 重複執行的代價只是幾次會被冪等擋掉的寫入，遠低於這個。
 */
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
