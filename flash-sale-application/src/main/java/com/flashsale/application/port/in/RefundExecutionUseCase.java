package com.flashsale.application.port.in;

import com.flashsale.domain.aftersales.event.RefundRequestedEvent;

/** 執行退款——退款 Saga 的慢車道（ADR-0011 決策 8）。 */
public interface RefundExecutionUseCase {

    void execute(RefundRequestedEvent event);
}
