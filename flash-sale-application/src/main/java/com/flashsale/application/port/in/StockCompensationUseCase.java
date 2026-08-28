package com.flashsale.application.port.in;

import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.domain.order.event.OrderCancelledEvent;

/**
 * 庫存補償入站埠——Saga 補償鏈的執行端。
 *
 * <p>兩個觸發來源：
 * <ul>
 *   <li>訂單被關閉（逾時未付款、使用者取消）→ {@link #compensate(OrderCancelledEvent)}</li>
 *   <li>建單訊息重試耗盡進入 DLQ（訂單根本沒建成）→ {@link #compensateDeadLetter}</li>
 * </ul>
 */
public interface StockCompensationUseCase {

    /** 依訂單關閉事件退回庫存。必須冪等。 */
    void compensate(OrderCancelledEvent event);

    /**
     * 處理進入死信佇列的建單訊息：退回庫存並標記請求失敗。
     *
     * <p>此時資料庫中沒有訂單紀錄，只能依賴訊息本身攜帶的 {@code requestId} 退庫。
     */
    void compensateDeadLetter(SeckillOrderMessage message, String reason);
}
