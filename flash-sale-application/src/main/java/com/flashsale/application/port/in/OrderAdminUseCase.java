package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.application.port.in.dto.PageView;
import java.util.Optional;

/** 後台的訂單查詢與關單。呼叫端必須已具備 admin scope；這裡不再驗身分。 */
public interface OrderAdminUseCase {

    /** 三個條件皆可為 null，AND 組合；新到舊。 */
    PageView<OrderView> search(String orderNo, Long userId, String status, int page, int size);

    /** 只回已落庫的訂單。還在佇列裡的單後台看不到——它還不是一張單。 */
    OrderView find(String orderNo);

    /**
     * 手動關閉待付款訂單。成功代表訂單已轉 CANCELLED、一般庫存已退回、
     * 秒殺退庫事件已寫入 Outbox；不代表秒殺庫存已經回到 Redis。
     * 非待付款狀態拋 {@code ILLEGAL_ORDER_STATE_TRANSITION}。
     */
    OrderView close(String orderNo, String reason);

    /** 後台跳轉 Tempo 用。訂單不存在時拋 ORDER_NOT_FOUND，沒有 trace 時回 empty。 */
    Optional<String> traceId(String orderNo);
}
