package com.flashsale.application.port.out;

import com.flashsale.domain.shared.DomainEvent;

import java.util.List;

/**
 * 領域事件發件匣埠（出站）。
 *
 * <p><b>必須與業務資料寫在同一個資料庫交易中</b>。這是本專案取代 Seata 的關鍵：
 * 「訂單落庫」與「事件投遞」不再是兩個需要協調的資源，而是同一次 commit 的兩張表，
 * 天然原子。之後由 {@code OutboxRelayScheduler} 非同步搬運到 MQ（至少一次語意）。
 *
 * <p>詳見 ADR-0004。
 */
public interface EventOutbox {

    /** 在當前交易中登記待發布事件。 */
    void append(List<DomainEvent> events);
}
