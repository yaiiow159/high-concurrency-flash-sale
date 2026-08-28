package com.flashsale.application.service;

import com.flashsale.domain.shared.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 秒殺鏈路的業務指標。
 *
 * <p>把 Micrometer 的呼叫集中在這裡，Use Case 內就不會散落 {@code registry.counter(...)}
 * 這類與業務無關的雜訊。指標名稱與標籤只在此處定義一次，Grafana 面板不會因為
 * 有人手滑打錯標籤而破圖。
 *
 * <p><b>標籤基數控制</b>：只用 {@code activityId} 與錯誤碼當標籤，
 * 絕不放 {@code userId}——那會讓 Prometheus 的時間序列數量爆炸。
 */
@Component
public class SeckillMetrics {

    private static final String ATTEMPT_TIMER = "seckill.attempt.duration";
    private static final String REJECTION_COUNTER = "seckill.rejection.total";
    private static final String COMPENSATION_COUNTER = "seckill.compensation.total";
    private static final String ORDER_PERSIST_COUNTER = "seckill.order.persist.total";

    private final MeterRegistry registry;

    public SeckillMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSuccess(Long activityId, long startNanos) {
        timer(activityId, "success").record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    public void recordRejection(Long activityId, ErrorCode errorCode, long startNanos) {
        timer(activityId, "rejected").record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        Counter.builder(REJECTION_COUNTER)
                .tag("activity", String.valueOf(activityId))
                .tag("code", errorCode.code())
                .description("搶購被拒次數，依錯誤碼分類")
                .register(registry)
                .increment();
    }

    /** 庫存補償結果；{@code success=false} 代表退庫失敗，需要人工或對帳排程介入。 */
    public void recordCompensation(Long activityId, String trigger, boolean success) {
        Counter.builder(COMPENSATION_COUNTER)
                .tag("activity", String.valueOf(activityId))
                .tag("trigger", trigger)
                .tag("result", success ? "success" : "failure")
                .description("庫存補償（Saga 回滾）執行次數")
                .register(registry)
                .increment();
    }

    public void recordOrderPersisted(Long activityId, String result) {
        Counter.builder(ORDER_PERSIST_COUNTER)
                .tag("activity", String.valueOf(activityId))
                .tag("result", result)
                .description("MQ 消費端訂單落庫結果")
                .register(registry)
                .increment();
    }

    private Timer timer(Long activityId, String result) {
        return Timer.builder(ATTEMPT_TIMER)
                .tag("activity", String.valueOf(activityId))
                .tag("result", result)
                .description("搶購請求端到端耗時（不含 MQ 消費）")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(3))
                .register(registry);
    }
}
