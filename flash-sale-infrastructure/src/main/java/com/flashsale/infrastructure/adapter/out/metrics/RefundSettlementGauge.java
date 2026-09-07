package com.flashsale.infrastructure.adapter.out.metrics;

import com.flashsale.application.port.out.ReturnRequestRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 已核可退款但錢還沒出去的筆數。
 *
 * <p>短暫非 0 是正常的（閘道呼叫需要時間），**持續非 0 才是訊號**：
 * 補送排程推不動它，代表閘道那一步真的卡住了。
 *
 * <p>每個節點各自量同一張表，不需要跨節點互斥（與 {@link OutboxDeadLetterGauge} 同理）。
 */
@Component
public class RefundSettlementGauge {

    private static final Logger log = LoggerFactory.getLogger(RefundSettlementGauge.class);

    private final ReturnRequestRepository returnRepository;
    private final MeterRegistry registry;
    private final AtomicLong awaitingCount = new AtomicLong();

    public RefundSettlementGauge(ReturnRequestRepository returnRepository, MeterRegistry registry) {
        this.returnRepository = returnRepository;
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        Gauge.builder("refund.awaiting-settlement.total", awaitingCount, AtomicLong::get)
                .description("已核可退款但錢還沒出去的筆數；持續非 0 代表閘道那一步卡住了")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${flash-sale.aftersales.settlement-scan-interval-ms:60000}")
    public void sample() {
        try {
            awaitingCount.set(returnRepository.countAwaitingSettlement());
        } catch (RuntimeException e) {
            // 量測失敗不該影響任何業務流程；下一輪再試
            log.warn("統計待到帳退款數失敗：{}", e.getMessage());
        }
    }
}
