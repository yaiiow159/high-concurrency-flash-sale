package com.flashsale.infrastructure.scheduler;

import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import com.flashsale.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.OutboxEventJpaRepository;
import com.flashsale.infrastructure.config.FlashSaleProperties;
import com.flashsale.infrastructure.tracing.TraceContexts;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 中繼的實際執行者。
 *
 * <p>與 {@code OutboxRelayScheduler} 分開是為了讓 {@code @Transactional} 真的生效——
 * 併回同一個 Bean 就會因為內部呼叫不過代理而安靜失效。
 */
@Component
public class OutboxRelayer {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayer.class);

    /** <b>整批</b>的投遞期限，不是每一筆的。 */
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxEventJpaRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FlashSaleProperties properties;
    private final TraceContexts traceContexts;
    private final Clock clock;

    public OutboxRelayer(OutboxEventJpaRepository outboxRepository,
                         KafkaTemplate<String, String> kafkaTemplate,
                         FlashSaleProperties properties,
                         TraceContexts traceContexts,
                         Clock clock) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.traceContexts = traceContexts;
        this.clock = clock;
    }

    /** 搬運一批待投遞事件。 */
    @Transactional
    public int relayPendingEvents() {
        List<OutboxEventEntity> pending =
                outboxRepository.findPending(Limit.of(properties.outbox().batchSize()));
        if (pending.isEmpty()) {
            return 0;
        }

        List<InFlight> inFlight = new ArrayList<>(pending.size());
        for (OutboxEventEntity event : pending) {
            dispatch(event).ifPresent(inFlight::add);
        }

        Instant deadline = clock.instant().plus(BATCH_TIMEOUT);
        int published = 0;
        for (InFlight sent : inFlight) {
            if (awaitAck(sent, deadline)) {
                published++;
            }
        }
        log.debug("Outbox 中繼完成：{}/{} 筆投遞成功", published, pending.size());
        return published;
    }

    /** 送出但不等待。 */
    private Optional<InFlight> dispatch(OutboxEventEntity event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaTopics.ORDER_EVENT, event.getAggregateId(), event.getPayload());
            // 事件型別放在標頭，消費端不必反序列化 payload 就能決定要不要處理。
            record.headers().add(KafkaTopics.HEADER_EVENT_TYPE,
                    event.getEventType().getBytes(StandardCharsets.UTF_8));
            // 在寫入時存下的 trace 底下送：KafkaTemplate 的 observation 會把它當父節點，
            // 消費端從 header 接續的就是同一條 trace（ADR-0029）
            CompletableFuture<SendResult<String, String>> future = traceContexts.runUnder(
                    event.getTraceContext(), "outbox.relay", () -> kafkaTemplate.send(record));
            return Optional.of(new InFlight(event, future));
        } catch (RuntimeException e) {
            markFailed(event, e);
            return Optional.empty();
        }
    }

    /** 等一筆的 ack。 */
    private boolean awaitAck(InFlight sent, Instant deadline) {
        long remaining = Duration.between(clock.instant(), deadline).toMillis();
        try {
            sent.future().get(Math.max(remaining, 0), TimeUnit.MILLISECONDS);
            sent.event().markPublished(clock.instant());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sent.event().markFailed("投遞被中斷", properties.outbox().maxRetry());
            return false;
        } catch (Exception e) {
            markFailed(sent.event(), e);
            return false;
        }
    }

    private void markFailed(OutboxEventEntity event, Exception cause) {
        event.markFailed(cause.getMessage(), properties.outbox().maxRetry());
        log.warn("Outbox 事件 {} 投遞失敗（第 {} 次）",
                event.getEventId(), event.getRetryCount(), cause);
    }

    /** 已送出、等待 ack 的一筆。把事件與它自己的 future 綁在一起，才能逐筆記錄成敗。 */
    private record InFlight(OutboxEventEntity event,
                            CompletableFuture<SendResult<String, String>> future) {
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
