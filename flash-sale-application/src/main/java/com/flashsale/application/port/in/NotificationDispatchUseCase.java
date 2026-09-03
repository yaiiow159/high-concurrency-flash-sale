package com.flashsale.application.port.in;

import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.domain.order.event.OrderCancelledEvent;
import com.flashsale.domain.order.event.OrderCompletedEvent;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.domain.order.event.OrderShippedEvent;

/**
 * 由領域事件產生通知。
 *
 * <p><b>冪等是必答題</b>：Outbox 是至少一次語意，同一個事件一定會被重複投遞，
 * 而重複的後果是使用者為同一次出貨收到三封一樣的信——
 * 那比漏寄更容易讓人關掉全部通知。
 *
 * <p>每個方法對應一個事件型別而不是收一個泛型的 {@code DomainEvent}：
 * 不同事件需要的欄位不同（付款要金額、出貨不要），
 * 用泛型介面會讓實作裡出現一串 instanceof。
 */
public interface NotificationDispatchUseCase {

    void onOrderPaid(OrderPaidEvent event);

    void onOrderShipped(OrderShippedEvent event);

    void onOrderCompleted(OrderCompletedEvent event);

    void onOrderCancelled(OrderCancelledEvent event);

    void onRefundRequested(RefundRequestedEvent event);
}
