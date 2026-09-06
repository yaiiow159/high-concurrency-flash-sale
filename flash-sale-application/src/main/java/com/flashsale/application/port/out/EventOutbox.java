package com.flashsale.application.port.out;

import com.flashsale.domain.shared.DomainEvent;

import java.util.List;

/** 領域事件發件匣埠（出站）。 */
public interface EventOutbox {

    /** 在當前交易中登記待發布事件。 */
    void append(List<DomainEvent> events);
}
