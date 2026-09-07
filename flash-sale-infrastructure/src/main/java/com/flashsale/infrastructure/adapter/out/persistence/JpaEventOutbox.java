package com.flashsale.infrastructure.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.OutboxEventJpaRepository;
import com.flashsale.infrastructure.tracing.TraceContexts;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 發件匣的 JPA 實作。 */
@Component
public class JpaEventOutbox implements EventOutbox {

    private final OutboxEventJpaRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final TraceContexts traceContexts;

    public JpaEventOutbox(OutboxEventJpaRepository outboxRepository, ObjectMapper objectMapper,
                          TraceContexts traceContexts) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.traceContexts = traceContexts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(List<DomainEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        // 同一批事件來自同一個請求，traceparent 抓一次就好
        String traceContext = traceContexts.current().orElse(null);
        outboxRepository.saveAll(events.stream().map(event -> toEntity(event, traceContext)).toList());
    }

    private OutboxEventEntity toEntity(DomainEvent event, String traceContext) {
        return new OutboxEventEntity(
                event.eventId(),
                event.eventType(),
                event.aggregateId(),
                serialize(event),
                event.occurredAt(),
                traceContext);
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 序列化失敗代表事件定義本身有問題，屬於程式錯誤而非執行期異常，
            // 讓交易回滾比寫入一筆永遠投遞不出去的紀錄更好。
            throw new IllegalStateException("領域事件序列化失敗: " + event.eventType(), e);
        }
    }
}
