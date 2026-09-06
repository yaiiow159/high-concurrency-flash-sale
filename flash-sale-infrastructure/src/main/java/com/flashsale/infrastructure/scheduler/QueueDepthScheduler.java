package com.flashsale.infrastructure.scheduler;

import com.flashsale.infrastructure.adapter.out.mq.KafkaOrderQueueDepth;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期取樣建單佇列的深度（ADR-0023）。 */
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
