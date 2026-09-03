package com.flashsale.application.port.in;

import com.flashsale.domain.aftersales.event.RefundRequestedEvent;

/**
 * 執行退款——退款 Saga 的慢車道（ADR-0011 決策 8）。
 *
 * <p>做兩件事：呼叫金流把錢退回去、把可再售的庫存補回一般庫存池。
 * 兩件都是遠端操作，因此都留在 MQ 消費端而不在核准退款的那個交易裡。
 *
 * <p><b>冪等是必答題</b>：Outbox 是至少一次語意，這個事件一定會被重複投遞。
 * 而重複執行的代價是把錢送出去兩次——沒有任何事後對帳能補救。
 */
public interface RefundExecutionUseCase {

    void execute(RefundRequestedEvent event);
}
