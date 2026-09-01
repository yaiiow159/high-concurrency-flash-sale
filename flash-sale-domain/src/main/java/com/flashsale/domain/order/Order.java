package com.flashsale.domain.order;

import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.event.OrderCancelledEvent;
import com.flashsale.domain.order.event.OrderCreatedEvent;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 訂單聚合根。
 *
 * <p>取代先前的單品項 {@code SeckillOrder}。秒殺訂單現在是
 * <b>「只有一條 line 的 Order」</b>——特例，不是另一種型別。
 * 沒有子類別、沒有多型：付款、履約、退款、對帳這些下游只認得一種訂單，
 * 才不會每新增一個下游就多一處分支（見 ADR-0007）。
 *
 * <p><b>金額反正規化存下來，而非每次由 lines 計算。</b>
 * 這樣做的前提是「訂單建立後金額不可變」——lines 不會增減、單價不會變，
 * 部分退款產生獨立的退款紀錄而非修改原訂單。
 * 因此「儲存值與計算值不一致」的風險為零。
 * <b>這條規則必須守住</b>，否則反正規化就從最佳化變成資料完整性風險。
 *
 * <p>所有狀態變更都必須經由行為方法，狀態欄位不對外開放 setter；
 * 非法轉移在聚合內就被擋下，不依賴呼叫端自律。
 */
public final class Order {

    private final OrderNo orderNo;
    private final Long userId;
    private final OrderChannel channel;
    /** 端到端冪等鍵。秒殺用它做庫存補償的憑據；一般下單用它擋重複提交。 */
    private final String requestId;
    private final List<OrderLine> lines;
    private final BigDecimal totalAmount;
    private final Instant createdAt;

    private OrderStatus status;
    private Instant paidAt;
    private String closeReason;
    private final long version;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private Order(OrderNo orderNo, Long userId, OrderChannel channel, String requestId,
                  List<OrderLine> lines, BigDecimal totalAmount, OrderStatus status,
                  Instant createdAt, Instant paidAt, String closeReason, long version) {
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo 不可為 null");
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.channel = Objects.requireNonNull(channel, "channel 不可為 null");
        this.requestId = Objects.requireNonNull(requestId, "requestId 不可為 null");
        this.lines = List.copyOf(requireNonEmpty(lines));
        this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount 不可為 null");
        this.status = Objects.requireNonNull(status, "status 不可為 null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        this.paidAt = paidAt;
        this.closeReason = closeReason;
        this.version = version;
    }

    /**
     * 建立一般訂單（可多品項）。
     *
     * @param requestId 冪等鍵，由呼叫端產生，用於擋重複提交
     */
    public static Order place(OrderNo orderNo, Long userId, String requestId,
                              List<OrderLine> lines, Instant now) {
        Order order = new Order(orderNo, userId, OrderChannel.NORMAL, requestId,
                lines, sumOf(lines), OrderStatus.PENDING_PAYMENT, now, null, null, 0L);
        order.registerEvent(OrderCreatedEvent.of(order, now));
        return order;
    }

    /**
     * 建立秒殺訂單——只有一條 line 的 {@link Order}。
     *
     * <p>金額與商品快照都由活動聚合提供，不接受呼叫端傳入：
     * 前端若能決定價格，那就不叫價格了。
     */
    public static Order forSeckill(OrderNo orderNo, SeckillActivity activity, Long userId,
                                   String requestId, int quantity, Instant now) {
        activity.ensureQuantityWithinLimit(quantity);
        OrderLine line = new OrderLine(
                activity.productId(),
                activity.productName(),
                activity.seckillPrice(),
                quantity,
                activity.id());

        Order order = new Order(orderNo, userId, OrderChannel.SECKILL, requestId,
                List.of(line), line.subtotal(), OrderStatus.PENDING_PAYMENT, now, null, null, 0L);
        order.registerEvent(OrderCreatedEvent.of(order, now));
        return order;
    }

    /**
     * 從持久化狀態重建，<b>不</b>產生領域事件。
     *
     * <p>與建立方法明確分離，避免 repository 載入既有訂單時誤觸發事件。
     */
    public static Order restore(OrderNo orderNo, Long userId, OrderChannel channel, String requestId,
                                List<OrderLine> lines, BigDecimal totalAmount, OrderStatus status,
                                Instant createdAt, Instant paidAt, String closeReason, long version) {
        return new Order(orderNo, userId, channel, requestId, lines, totalAmount,
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
    public boolean isPaymentExpiredAt(Instant now, Duration paymentWindow) {
        return status == OrderStatus.PENDING_PAYMENT && now.isAfter(createdAt.plus(paymentWindow));
    }

    public boolean belongsTo(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }

    /**
     * 此訂單佔用了某活動多少數量。
     *
     * <p>對帳用。走 lines 而非單一欄位，因為一張訂單理論上可含多條同活動的行。
     */
    public int quantityFromActivity(Long activityId) {
        return lines.stream()
                .filter(line -> line.isFromActivity(activityId))
                .mapToInt(OrderLine::quantity)
                .sum();
    }

    /** 全部品項的總數量。 */
    public int totalQuantity() {
        return lines.stream().mapToInt(OrderLine::quantity).sum();
    }

    /** 秒殺訂單只有一條 line；供需要單一商品資訊的舊有流程使用。 */
    public OrderLine soleLine() {
        if (lines.size() != 1) {
            throw new IllegalStateException(
                    "訂單 %s 有 %d 條 line，不存在唯一的一條".formatted(orderNo, lines.size()));
        }
        return lines.getFirst();
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

    private static BigDecimal sumOf(List<OrderLine> lines) {
        return requireNonEmpty(lines).stream()
                .map(OrderLine::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<OrderLine> requireNonEmpty(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "訂單至少要有一條訂單行");
        }
        return lines;
    }

    public OrderNo orderNo() {
        return orderNo;
    }

    public Long userId() {
        return userId;
    }

    public OrderChannel channel() {
        return channel;
    }

    public String requestId() {
        return requestId;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public BigDecimal totalAmount() {
        return totalAmount;
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
        return o instanceof Order other && Objects.equals(orderNo, other.orderNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderNo);
    }

    @Override
    public String toString() {
        return "Order{orderNo=%s, userId=%d, channel=%s, status=%s, lines=%d}"
                .formatted(orderNo, userId, channel, status, lines.size());
    }
}
