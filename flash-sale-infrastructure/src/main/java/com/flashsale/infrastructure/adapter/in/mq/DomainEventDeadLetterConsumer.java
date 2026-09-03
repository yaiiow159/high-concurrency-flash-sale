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

/**
 * 領域事件的死信處理。
 *
 * <p><b>它不修任何東西，只保證失敗被看見。</b>
 * 先前 {@code seckill.order.event.DLT} 只有 topic 宣告而沒有任何消費端——
 * 任何領域事件重試耗盡後就靜默消失，沒有日誌、沒有指標、沒有告警。
 *
 * <p>對多數事件而言那只是延遲；對 {@code refund.requested} 而言是<b>錢</b>：
 * 送出退款的交易早已 commit（付款單記為已退、退貨單進終態），
 * 事件消失代表帳上退了、錢沒退、庫存也沒回補，而且沒有人會知道。
 *
 * <p>刻意不自動重投。事件會走到這裡，代表它已經失敗過
 * {@code MAX_ATTEMPTS} 次或被判定為不可重試；再自動試一次多半只是
 * 再失敗一次，卻讓真正的原因更難查。這裡的責任是把它變成一個
 * <b>看得到的數字</b>，由人決定怎麼處理——與對帳不自動修復同一個判斷。
 */
@Component
public class DomainEventDeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventDeadLetterConsumer.class);

    /**
     * 用點號分隔，與專案其他指標一致（Micrometer 會依註冊表轉換成該系統的慣例，
     * Prometheus 上會是 {@code domain_event_dead_letter_total}）。
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
