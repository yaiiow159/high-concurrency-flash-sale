package com.flashsale.infrastructure.adapter.in.mq;

import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** 領域事件的死信處理。 */
@Component
public class DomainEventDeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventDeadLetterConsumer.class);

    /**
     * 用點號分隔，與專案其他指標一致——Micrometer 會依註冊表轉成該系統的慣例
     * （Prometheus 上是 {@code domain_event_dead_letter_total}）。
     * 直接寫底線雖然在 Prometheus 上結果相同，但換一種註冊表時就只有這一個名字不對。
     */
    private static final String METRIC = "domain.event.dead-letter.total";

    private final MeterRegistry meterRegistry;

    public DomainEventDeadLetterConsumer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT_DLT,
            groupId = "${flash-sale.mq.event-dlt-group:domain-event-dead-letter}",
            concurrency = "1")
    public void onDeadLetter(@Payload String payload,
                             @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                             String eventType,
                             @Header(name = "kafka_dlt-exception-message", required = false)
                             String failureReason) {
        String type = eventType == null ? "unknown" : eventType;

        // 依事件型別分標籤：退款進死信與出貨進死信的嚴重度差了一個量級，
        // 混成同一個計數器就沒辦法只對前者告警
        Counter.builder(METRIC)
                .tag("eventType", type)
                .register(meterRegistry)
                .increment();

        // payload 完整記下來，因為要人工處理時它就是唯一的依據
        log.error("領域事件進入死信，需要人工處理 eventType={}, 原因={}, payload={}",
                type, failureReason, payload);
    }
}
