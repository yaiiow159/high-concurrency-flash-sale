package com.flashsale.application.service;

import com.flashsale.application.port.in.dto.SkuReconciliation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 一般庫存的業務指標。
 *
 * <p><b>標籤基數是這裡最需要小心的事。</b>SKU 會成長到數萬個，
 * 若像秒殺那樣把 ID 當標籤，Prometheus 的時間序列數會直接爆掉——
 * 秒殺活動同時只有幾場，SKU 不是。
 *
 * <p>因此這裡只記「有幾個 SKU 不平」與偏差方向，<b>不記是哪一個</b>。
 * 要知道是哪一個，看日誌或呼叫對帳端點——那是排查時才需要的資訊，
 * 不該讓每個 SKU 在監控系統裡常駐一條時間序列。
 */
@Component
public class InventoryMetrics {

    private static final String RECONCILIATION_COUNTER = "inventory.reconciliation.total";
    private static final String ALLOCATION_COUNTER = "inventory.allocation.total";

    private final MeterRegistry registry;

    public InventoryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSkuReconciliation(SkuReconciliation result) {
        Counter.builder(RECONCILIATION_COUNTER)
                .tag("verdict", result.verdict().name())
                .description("一般庫存對帳結果，依判定分類")
                .register(registry)
                .increment();
    }

    /** @param action {@code allocate} 或 {@code release} */
    public void recordAllocation(String action, boolean applied) {
        Counter.builder(ALLOCATION_COUNTER)
                .tag("action", action)
                // 「已執行過而略過」與「本次執行」要分開計數：
                // 前者持續增加是正常的冪等行為，後者才代表真的有活動在上下架
                .tag("result", applied ? "applied" : "skipped")
                .description("庫存劃撥與釋放次數")
                .register(registry)
                .increment();
    }
}
