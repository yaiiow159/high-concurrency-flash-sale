package com.flashsale.application.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 搜尋索引的對帳指標。
 *
 * <p>用 Gauge 而不是 Counter：這裡要看的是<b>「現在差多少」</b>，
 * 不是「歷來累積差過多少」。累積量在修好之後仍然居高不下，
 * 那種指標沒辦法拿來設告警。
 */
@Component
public class SearchIndexMetrics {

    private final AtomicLong missing = new AtomicLong();
    private final AtomicLong orphaned = new AtomicLong();

    public SearchIndexMetrics(MeterRegistry registry) {
        Gauge.builder("search.index.drift", missing, AtomicLong::get)
                .tag("direction", "missing")
                .description("在資料庫已上架、卻不在搜尋索引裡的商品數（症狀：搜不到）")
                .register(registry);
        Gauge.builder("search.index.drift", orphaned, AtomicLong::get)
                .tag("direction", "orphaned")
                .description("在搜尋索引裡、卻已不是上架狀態的商品數（症狀：搜到了但買不到）")
                .register(registry);
    }

    public void recordReconciliation(long missingCount, long orphanedCount) {
        missing.set(missingCount);
        orphaned.set(orphanedCount);
    }
}
