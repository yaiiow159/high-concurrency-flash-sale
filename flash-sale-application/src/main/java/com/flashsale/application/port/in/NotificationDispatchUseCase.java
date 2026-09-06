package com.flashsale.application.port.in;

import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.domain.order.event.OrderCancelledEvent;
import com.flashsale.domain.order.event.OrderCompletedEvent;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.domain.order.event.OrderShippedEvent;

/** 由領域事件產生通知。 */
public interface NotificationDispatchUseCase {

    void onOrderPaid(OrderPaidEvent event);

    void onOrderShipped(OrderShippedEvent event);

    void onOrderCompleted(OrderCompletedEvent event);

    void onOrderCancelled(OrderCancelledEvent event);

    void onRefundRequested(RefundRequestedEvent event);
}
