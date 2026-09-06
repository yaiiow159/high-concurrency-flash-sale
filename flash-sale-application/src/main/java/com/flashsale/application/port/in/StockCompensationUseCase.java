package com.flashsale.application.port.in;

import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.domain.order.event.OrderCancelledEvent;

/** 庫存補償入站埠——Saga 補償鏈的執行端。 */
public interface StockCompensationUseCase {

    /** 依訂單關閉事件退回庫存。必須冪等。 */
    void compensate(OrderCancelledEvent event);

    /** 處理進入死信佇列的建單訊息：退回庫存並標記請求失敗。 */
    void compensateDeadLetter(SeckillOrderMessage message, String reason);
}
