package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ShipmentView;
import com.flashsale.domain.fulfillment.Carrier;
import com.flashsale.domain.fulfillment.ShipmentStatus;
import com.flashsale.domain.order.event.OrderPaidEvent;

import java.util.List;

/**
 * 履約：出貨、配送狀態、送達。
 *
 * <p>出貨單由訂單付款事件觸發建立，而非下單當下——
 * 沒付錢的訂單不該進入揀貨佇列。
 */
public interface FulfillmentUseCase {

    /**
     * 消費 {@code order.paid} 事件建立出貨單。
     *
     * <p><b>必須冪等</b>：Outbox 是至少一次語意，同一個付款事件會被重複投遞。
     * 重複建立的後果是同一張訂單出兩次貨。
     */
    void prepareShipment(OrderPaidEvent event);

    /** 交付承運商。物流單號必填。 */
    ShipmentView dispatch(String orderNo, Carrier carrier, String trackingNumber);

    /** 標記送達，訂單同步轉為完成。 */
    ShipmentView markDelivered(String orderNo);

    /** 配送失敗；可重新派送，不是終態。 */
    ShipmentView markFailed(String orderNo, String reason);

    /** 使用者查詢自己的出貨進度。 */
    ShipmentView findForUser(String orderNo, Long userId);

    /** 營運後台的待處理清單。 */
    List<ShipmentView> listByStatus(ShipmentStatus status, int limit);
}
