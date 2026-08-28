package com.flashsale.infrastructure.scheduler;

import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import com.flashsale.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.OutboxEventJpaRepository;
import com.flashsale.infrastructure.config.FlashSaleProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 中繼的實際執行者。
 *
 * <p><b>為什麼要從 {@link OutboxRelayScheduler} 拆出來？</b>
 * Spring 的 {@code @Transactional} 是靠動態代理實現的，同一個 Bean 內部的方法呼叫
 * （{@code this.method()}）走的是原始物件，<b>完全不會經過代理</b>——
 * 註解會安靜地失效，交易根本沒開，而且沒有任何錯誤訊息。
 *
 * <p>把交易方法放到另一個 Bean 並透過依賴注入呼叫，代理才會生效。
 * 這是 Spring 最常見也最難察覺的陷阱之一，值得為它多一個類別。
 */
@Component
public class OutboxRelayer {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayer.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final OutboxEventJpaRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FlashSaleProperties properties;
    private final Clock clock;

    public OutboxRelayer(OutboxEventJpaRepository outboxRepository,
                         KafkaTemplate<String, String> kafkaTemplate,
                         FlashSaleProperties properties,
                         Clock clock) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 搬運一批待投遞事件。
     *
     * <p>整批在同一個交易內：實體的狀態變更靠 JPA 的髒檢查在 commit 時一次寫回，
     * 省下逐筆 UPDATE 的往返。
     *
     * @return 本次成功投遞的筆數
     */
    @Transactional
    public int relayPendingEvents() {
        List<OutboxEventEntity> pending =
                outboxRepository.findPending(Limit.of(properties.outbox().batchSize()));
        if (pending.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEventEntity event : pending) {
            if (publishOne(event)) {
                published++;
            }
        }
        log.debug("Outbox 中繼完成：{}/{} 筆投遞成功", published, pending.size());
        return published;
    }

    /**
     * 逐筆同步投遞。
     *
     * <p>批次非同步投遞吞吐更高，但無法精確知道「哪幾筆成功」，
     * 只能整批標記或整批重試——重試時已成功的那些會被重複投遞。
     * Outbox 的量級攤平在時間軸上，不需要極致吞吐，換取精確的狀態追蹤是划算的。
     */
    private boolean publishOne(OutboxEventEntity event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaTopics.ORDER_EVENT, event.getAggregateId(), event.getPayload());
            // 事件型別放在標頭，消費端不必反序列化 payload 就能決定要不要處理。
            record.headers().add(KafkaTopics.HEADER_EVENT_TYPE,
                    event.getEventType().getBytes(StandardCharsets.UTF_8));

            kafkaTemplate.send(record).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished(clock.instant());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.markFailed("投遞被中斷", properties.outbox().maxRetry());
            return false;
        } catch (Exception e) {
            event.markFailed(e.getMessage(), properties.outbox().maxRetry());
            log.warn("Outbox 事件 {} 投遞失敗（第 {} 次）", event.getEventId(), event.getRetryCount(), e);
            return false;
        }
    }

    /** 清理已投遞的舊紀錄，避免發件匣表隨訂單量無限成長。 */
    @Transactional
    public int deleteOldPublishedEvents() {
        int retentionDays = properties.outbox().retentionDays();
        int deleted = outboxRepository.deletePublishedBefore(
                clock.instant().minus(Duration.ofDays(retentionDays)));
        if (deleted > 0) {
            log.info("清理 {} 天前已投遞的 Outbox 紀錄：{} 筆", retentionDays, deleted);
        }
        return deleted;
    }
}
