package com.flashsale.infrastructure.adapter.out.metrics;

import com.flashsale.application.port.out.SeckillLiveCounters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 從 MeterRegistry 讀回秒殺指標。名稱與標籤是與 {@link MicrometerSeckillMetrics} 的契約，
 * 那邊改了這邊要跟著改——否則監控頁會安靜地全部變成 0。
 */
@Component
public class MicrometerSeckillLiveCounters implements SeckillLiveCounters {

    private final MeterRegistry registry;

    public MicrometerSeckillLiveCounters(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Snapshot read(Long activityId) {
        String activity = String.valueOf(activityId);
        Map<String, Long> attempts = new LinkedHashMap<>();
        double p95 = 0;
        double p99 = 0;
        for (Timer timer : registry.find("seckill.attempt.duration").tag("activity", activity).timers()) {
            attempts.merge(timer.getId().getTag("result"), timer.count(), Long::sum);
            for (ValueAtPercentile value : timer.takeSnapshot().percentileValues()) {
                if (value.percentile() == 0.95) {
                    p95 = Math.max(p95, value.value(TimeUnit.MILLISECONDS));
                } else if (value.percentile() == 0.99) {
                    p99 = Math.max(p99, value.value(TimeUnit.MILLISECONDS));
                }
            }
        }
        return new Snapshot(
                attempts,
                sumBy("seckill.rejection.total", activity, "code"),
                sumBy("seckill.publish.total", activity, "outcome"),
                sumBy("seckill.compensation.total", activity, "result"),
                sumBy("seckill.order.persist.total", activity, "result"),
                p95, p99);
    }

    private Map<String, Long> sumBy(String meter, String activity, String tag) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Counter counter : registry.find(meter).tag("activity", activity).counters()) {
            String key = counter.getId().getTag(tag);
            result.merge(key == null ? "unknown" : key, (long) counter.count(), Long::sum);
        }
        return result;
    }
}
