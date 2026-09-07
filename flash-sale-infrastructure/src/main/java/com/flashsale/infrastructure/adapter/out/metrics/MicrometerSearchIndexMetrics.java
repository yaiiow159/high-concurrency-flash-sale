package com.flashsale.infrastructure.adapter.out.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.flashsale.application.port.out.SearchIndexMetrics;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** 搜尋索引的對帳指標。 */
@Component
public class MicrometerSearchIndexMetrics implements SearchIndexMetrics {

    private final AtomicLong missing = new AtomicLong();
    private final AtomicLong orphaned = new AtomicLong();

    public MicrometerSearchIndexMetrics(MeterRegistry registry) {
        Gauge.builder("search.index.drift", missing, AtomicLong::get)
                .tag("direction", "missing")
                .description("在資料庫已上架、卻不在搜尋索引裡的商品數（症狀：搜不到）")
                .register(registry);
        Gauge.builder("search.index.drift", orphaned, AtomicLong::get)
                .tag("direction", "orphaned")
                .description("在搜尋索引裡、卻已不是上架狀態的商品數（症狀：搜到了但買不到）")
                .register(registry);
    }

    @Override
    public void recordReconciliation(long missingCount, long orphanedCount) {
        missing.set(missingCount);
        orphaned.set(orphanedCount);
    }
}
