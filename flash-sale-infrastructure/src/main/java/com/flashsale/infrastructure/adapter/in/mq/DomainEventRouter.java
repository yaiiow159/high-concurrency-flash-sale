package com.flashsale.infrastructure.adapter.in.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 事件分派：型別相符才反序列化並交給處理器，否則直接忽略。 */
@Component
public class DomainEventRouter {

    private final ObjectMapper objectMapper;

    public DomainEventRouter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 事件型別相符時反序列化並執行 {@code handler}。 */
    public <T> boolean route(String payload, String eventType, String expectedType,
                             Class<T> target, ThrowingHandler<T> handler) throws Exception {
        if (!expectedType.equals(eventType)) {
            return false;
        }
        handler.handle(objectMapper.readValue(payload, target));
        return true;
    }

    /** 允許拋出受檢例外的處理器。 */
    @FunctionalInterface
    public interface ThrowingHandler<T> {
        void handle(T event) throws Exception;
    }
}
