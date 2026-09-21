package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.OrderView;

/** 買家取消自己的待付款訂單。 */
public interface CancelOrderUseCase {

    /**
     * 成功代表訂單已關閉、一般庫存已退回、秒殺庫存的退庫事件已寫入 Outbox；
     * <b>不代表秒殺庫存已經回到 Redis</b>——那一步由事件非同步完成。
     *
     * <p>只接受待付款且沒有付款在途的訂單。查不到與不是本人一律回「訂單不存在」。
     */
    OrderView cancel(String orderNo, Long userId);
}
