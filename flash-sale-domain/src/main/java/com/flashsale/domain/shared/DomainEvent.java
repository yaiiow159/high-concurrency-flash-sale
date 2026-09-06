package com.flashsale.domain.shared;

import java.time.Instant;

/** 領域事件標記介面。 */
public interface DomainEvent {

    /** 事件唯一識別，同時作為消費端的冪等鍵。 */
    String eventId();

    /** 事件型別，決定投遞的 topic 與消費端路由。 */
    String eventType();

    /** 聚合根識別，作為 MQ partition key，保證同一訂單的事件有序。 */
    String aggregateId();

    Instant occurredAt();
}
