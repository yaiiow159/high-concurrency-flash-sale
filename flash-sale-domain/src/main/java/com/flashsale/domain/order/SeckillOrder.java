package com.flashsale.domain.order;

import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.event.OrderCancelledEvent;
import com.flashsale.domain.order.event.OrderCreatedEvent;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 秒殺訂單聚合根。
 *
 * <p>所有狀態變更都必須經由此處的行為方法，狀態欄位不對外開放 setter；
 * 非法轉移在聚合內就被擋下，不依賴呼叫端自律。
 *
 * <p><b>事件蒐集</b>：狀態變更會登記領域事件，由應用層在同一個資料庫交易中
 * 取出並寫入 Outbox 表，確保「狀態變更」與「事件投遞」原子一致（見 ADR-0004）。
 */
public final class SeckillOrder {

    private final OrderNo orderNo;
    private final Long activityId;
    private final Long userId;
    /** 產生此訂單的原始請求識別，庫存補償時作為 Lua 腳本的冪等鍵。 */
    private final String requestId;
    private final int quantity;
    private final BigDecimal amount;
    private final Instant createdAt;

    private OrderStatus status;
    private Instant paidAt;
    private String closeReason;
    private long version;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private SeckillOrder(OrderNo orderNo, Long activityId, Long userId, String requestId,
                         int quantity, BigDecimal amount, OrderStatus status,
                         Instant createdAt, Instant paidAt, String closeReason, long version) {
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo 不可為 null");
        this.activityId = Objects.requireNonNull(activityId, "activityId 不可為 null");
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.requestId = Objects.requireNonNull(requestId, "requestId 不可為 null");
        this.quantity = quantity;
        this.amount = Objects.requireNonNull(amount, "amount 不可為 null");
        this.status = Objects.requireNonNull(status, "status 不可為 null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        this.paidAt = paidAt;
        this.closeReason = closeReason;
        this.version = version;
    }

    /**
     * 建立新訂單（庫存已於 Redis 預扣成功後才會走到這裡）。
     *
     * <p>金額由活動聚合計算，而非由呼叫端傳入——避免前端竄改價格。
     */
    public static SeckillOrder create(OrderNo orderNo, SeckillActivity activity, Long userId,
                                      String requestId, int quantity, Instant now) {
        activity.ensureQuantityWithinLimit(quantity);
        SeckillOrder order = new SeckillOrder(
                orderNo, activity.id(), userId, requestId, quantity,
                activity.calculateAmount(quantity), OrderStatus.PENDING_PAYMENT,
                now, null, null, 0L);
        order.registerEvent(OrderCreatedEvent.of(order, now));
        return order;
    }

    /**
     * 從持久化狀態重建聚合，<b>不</b>產生領域事件。
     *
     * <p>與 {@link #create} 明確分離，避免 repository 載入既有訂單時誤觸發事件。
     */
    public static SeckillOrder restore(OrderNo orderNo, Long activityId, Long userId, String requestId,
                                       int quantity, BigDecimal amount, OrderStatus status,
                                       Instant createdAt, Instant paidAt, String closeReason, long version) {
        return new SeckillOrder(orderNo, activityId, userId, requestId, quantity, amount,
                status, createdAt, paidAt, closeReason, version);
    }

    /** 付款成功。 */
    public void pay(Instant paidAt) {
        transitionTo(OrderStatus.PAID);
        this.paidAt = paidAt;
        registerEvent(OrderPaidEvent.of(this, paidAt));
    }

    /** 取消訂單（逾時未付款或使用者主動取消），會觸發庫存補償事件。 */
    public void cancel(String reason, Instant now) {
        transitionTo(OrderStatus.CANCELLED);
        this.closeReason = reason;
        registerEvent(OrderCancelledEvent.of(this, reason, now));
    }

    /** 標記建單流程失敗（重試耗盡進 DLQ），同樣觸發庫存補償事件。 */
    public void markFailed(String reason, Instant now) {
        transitionTo(OrderStatus.FAILED);
        this.closeReason = reason;
        registerEvent(OrderCancelledEvent.of(this, reason, now));
    }

    /** 是否已逾付款期限。 */
    public boolean isPaymentExpiredAt(Instant now, java.time.Duration paymentWindow) {
        return status == OrderStatus.PENDING_PAYMENT && now.isAfter(createdAt.plus(paymentWindow));
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.ILLEGAL_ORDER_STATE_TRANSITION,
                    "訂單 %s 無法從 %s 轉為 %s".formatted(orderNo, status, target));
        }
        this.status = target;
    }

    private void registerEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    /** 取出並清空待發布的領域事件；應由應用層在交易內呼叫一次。 */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> pulled = List.copyOf(domainEvents);
        domainEvents.clear();
        return pulled;
    }

    public OrderNo orderNo() {
        return orderNo;
    }

    public Long activityId() {
        return activityId;
    }

    public Long userId() {
        return userId;
    }

    public String requestId() {
        return requestId;
    }

    public int quantity() {
        return quantity;
    }

    public BigDecimal amount() {
        return amount;
    }

    public OrderStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public String closeReason() {
        return closeReason;
    }

    public long version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SeckillOrder other && Objects.equals(orderNo, other.orderNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderNo);
    }

    @Override
    public String toString() {
        return "SeckillOrder{orderNo=%s, activityId=%d, userId=%d, status=%s}"
                .formatted(orderNo, activityId, userId, status);
    }
}
