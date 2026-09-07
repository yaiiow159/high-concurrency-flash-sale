package com.flashsale.application.service;

import com.flashsale.application.port.in.dto.ActivityReconciliation;
import com.flashsale.domain.shared.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 秒殺鏈路的業務指標。 */
@Component
public class SeckillMetrics {

    private static final String ATTEMPT_TIMER = "seckill.attempt.duration";
    private static final String REJECTION_COUNTER = "seckill.rejection.total";
    private static final String COMPENSATION_COUNTER = "seckill.compensation.total";
    private static final String ORDER_PERSIST_COUNTER = "seckill.order.persist.total";
    private static final String RECONCILIATION_COUNTER = "seckill.reconciliation.total";
    private static final String ORPHAN_COUNTER = "seckill.orphan.binding.total";
    private static final String DRIFT_GAUGE = "seckill.stock.drift";

    private final MeterRegistry registry;

    /** 各活動的庫存偏差值。 */
    private final Map<Long, AtomicLong> driftGauges = new ConcurrentHashMap<>();

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

    /** 資格預檢結果：granted 或被拒的錯誤碼名稱。被拒的比例一高就是有人在刷。 */
    public void recordQualification(String result) {
        Counter.builder("seckill_qualification_total")
                .tag("result", result)
                .description("搶購資格預檢結果")
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

    /** 記錄一次對帳結果。 */
    public void recordReconciliation(ActivityReconciliation result) {
        Counter.builder(RECONCILIATION_COUNTER)
                .tag("activity", String.valueOf(result.activityId()))
                .tag("verdict", result.verdict().name())
                .description("庫存對帳執行次數，依判定結果分類")
                .register(registry)
                .increment();

        driftGauges.computeIfAbsent(result.activityId(), activityId -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder(DRIFT_GAUGE, holder, AtomicLong::get)
                    .tag("activity", String.valueOf(activityId))
                    .description("Redis 實際餘量與依訂單推算的應有餘量之差；恆為 0 才是健康")
                    .register(registry);
            return holder;
        }).set(result.drift());
    }

    /** 孤兒扣減的偵測與修復結果。{@code action} 為 detected / repaired / repair-failed 等。 */
    public void recordOrphanBinding(Long activityId, String action) {
        Counter.builder(ORPHAN_COUNTER)
                .tag("activity", String.valueOf(activityId))
                .tag("action", action)
                .description("庫存已扣但查無訂單的孤兒扣減")
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
