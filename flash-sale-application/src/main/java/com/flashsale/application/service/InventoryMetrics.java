package com.flashsale.application.service;

import com.flashsale.application.port.in.dto.SkuReconciliation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 一般庫存的業務指標。 */
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
