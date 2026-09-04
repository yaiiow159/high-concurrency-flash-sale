package com.flashsale.infrastructure.scheduler;

import com.flashsale.infrastructure.adapter.out.mq.KafkaOrderQueueDepth;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期取樣建單佇列的深度（ADR-0023）。
 *
 * <p>排程與取樣邏輯分成兩個類別，與 {@code OutboxRelayScheduler} 同理：
 * 排程觸發器不該同時承擔業務邏輯，那會讓取樣本身沒辦法單獨測試。
 *
 * <p>這是<b>慢車道</b>。熱路徑只讀它寫下的記憶體值，不會碰到這裡的 I/O。
 */
@Component
public class QueueDepthScheduler {

    private final KafkaOrderQueueDepth queueDepth;

    public QueueDepthScheduler(KafkaOrderQueueDepth queueDepth) {
        this.queueDepth = queueDepth;
    }

    @Scheduled(fixedDelayString = "${flash-sale.admission.sample-interval-millis:5000}")
    public void sample() {
        queueDepth.sample();
    }
}
