package com.flashsale.infrastructure.adapter.out.metrics;

import com.flashsale.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 投遞已放棄（DEAD）的 Outbox 事件數；**恆為 0 才健康**。
 *
 * <p>DEAD 的意思是「這個事件永遠不會被投遞」，而下游可能是退庫、通知、積分。
 * 先前沒有任何東西讀它：清理排程只清 PUBLISHED，告警規則的處置指引寫著
 * 「檢查 outbox_event 的 DEAD 紀錄」，卻沒有一個指標看得到它——
 * 少於庫存漂移告警門檻的洩漏因此完全不可見。
 *
 * <p>每個節點各自量同一張表，不需要跨節點互斥（與 {@code QueueDepthScheduler} 同理）。
 */
@Component
public class OutboxDeadLetterGauge {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeadLetterGauge.class);

    private final OutboxEventJpaRepository outboxRepository;
    private final MeterRegistry registry;
    private final AtomicLong deadCount = new AtomicLong();

    public OutboxDeadLetterGauge(OutboxEventJpaRepository outboxRepository, MeterRegistry registry) {
        this.outboxRepository = outboxRepository;
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        Gauge.builder("outbox.dead.total", deadCount, AtomicLong::get)
                .description("投遞已放棄的 Outbox 事件數；非 0 代表有下游動作永遠不會發生")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${flash-sale.outbox.dead-scan-interval-ms:60000}")
    @Transactional(readOnly = true)
    public void sample() {
        try {
            long current = outboxRepository.countByStatus(OutboxEventEntity.STATUS_DEAD);
            long previous = deadCount.getAndSet(current);
            if (current > previous) {
                log.error("Outbox 有 {} 筆事件投遞已放棄，下游動作不會發生，需要人工處理", current);
            }
        } catch (RuntimeException e) {
            // 量測失敗不該影響任何業務流程；下一輪再試
            log.warn("統計 Outbox 死信數失敗：{}", e.getMessage());
        }
    }
}
