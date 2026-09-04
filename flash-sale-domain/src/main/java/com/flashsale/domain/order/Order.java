package com.flashsale.domain.order;

import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.event.OrderCancelledEvent;
import com.flashsale.domain.order.event.OrderCreatedEvent;
import com.flashsale.domain.order.event.OrderCompletedEvent;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.domain.order.event.OrderShippedEvent;
import com.flashsale.domain.shipping.ShippingMethod;
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

    /**
     * 運費。<b>不計入 {@link #totalAmount}</b>（ADR-0019 決策 1）。
     *
     * <p>那條恆等式（{@code totalAmount == Σ allocatedAmount}）是退款按行退的基礎，
     * 而運費不分攤到行——三件商品一起寄，退掉其中一件，
     * 配送已經發生了，沒有「三分之一趟」這種東西。
     *
     * <p>已經扣掉免運折抵，是<b>實際要收的</b>那個數字。
     */
    private final BigDecimal shippingFee;

    private final ShippingMethod shippingMethod;
    /**
     * 收貨資訊快照，可能為 null。
     *
     * <p>秒殺訂單在建立當下沒有地址——搶購請求只帶活動與數量，
     * 中間沒有讓使用者選地址的環節。那不是遺漏，是那條通道的形狀：
     * 削峰的前提就是把非必要的步驟移出下單當下。
     * 秒殺訂單的收貨資訊要在付款前補齊，屬於結帳流程的範圍。
     *
     * <p><b>一旦有值就不可再變</b>（{@code OrderEntity} 以
     * {@code updatable = false} 鎖住），理由見 {@link ShippingInfo}。
     */
    private final ShippingInfo shippingInfo;
    private final Instant createdAt;

    private OrderStatus status;
    private Instant paidAt;
    private String closeReason;
    private final long version;

    /**
     * 已套用的折扣<b>明細</b>，不是總額。
     *
     * <p>存明細是因為客服要回答的是「為什麼折了 320」而不是「折了多少」。
     * 而優惠會下架、券會過期、規則會改——這份清單是快照，
     * 與 {@code OrderLine} 的商品名稱與單價同一個道理。
     */
    private final List<OrderDiscount> discounts;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private Order(OrderNo orderNo, Long userId, OrderChannel channel, String requestId,
                  List<OrderLine> lines, BigDecimal totalAmount, ShippingInfo shippingInfo,
                  OrderStatus status, Instant createdAt, Instant paidAt,
                  String closeReason, List<OrderDiscount> discounts, long version,
                  BigDecimal shippingFee, ShippingMethod shippingMethod) {
        this.shippingFee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
        this.shippingMethod = shippingMethod == null
                ? ShippingMethod.HOME_DELIVERY : shippingMethod;
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo 不可為 null");
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.channel = Objects.requireNonNull(channel, "channel 不可為 null");
        this.requestId = Objects.requireNonNull(requestId, "requestId 不可為 null");
        this.lines = List.copyOf(requireNonEmpty(lines));
        this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount 不可為 null");
        this.shippingInfo = shippingInfo;
        this.status = Objects.requireNonNull(status, "status 不可為 null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        this.paidAt = paidAt;
        this.closeReason = closeReason;
        this.discounts = List.copyOf(discounts == null ? List.<OrderDiscount>of() : discounts);
        this.version = version;
    }

    /**
     * 建立一般訂單（可多品項）。
     *
     * @param requestId    冪等鍵，由呼叫端產生，用於擋重複提交
     * @param shippingInfo 收貨資訊<b>快照</b>，不可為 null——寄不出去的訂單不該被建立。
     *                     傳入的必須是快照而非地址簿的引用，理由見 {@link ShippingInfo}
     */
    public static Order place(OrderNo orderNo, Long userId, String requestId,
                              List<OrderLine> lines, ShippingInfo shippingInfo, Instant now) {
        return place(orderNo, userId, requestId, lines, shippingInfo, List.of(), sumOf(lines), now);
    }

    /**
     * 建立帶優惠的訂單。
     *
     * <p><b>折扣存的是明細不是總額</b>（ADR-0013 決策 3）。
     * 客服要回答的是「為什麼折了 320」，那需要知道是哪幾個優惠、各折多少。
     * 而優惠會下架、券會過期、規則會改——存 {@code promotionId} 讓畫面自己去查，
     * 就是 {@code OrderLine} 的快照已經解決過的同一個問題。
     *
     * @param payable 折後應付。由 {@code PricingEngine} 算出後傳入，
     *                聚合根不自己算——計算引擎是純函式，把它塞進聚合根
     *                會讓「建立訂單」這件事跟著優惠規則一起變複雜
     */
    public static Order place(OrderNo orderNo, Long userId, String requestId,
                              List<OrderLine> lines, ShippingInfo shippingInfo,
                              List<OrderDiscount> discounts, BigDecimal payable, Instant now) {
        return place(orderNo, userId, requestId, lines, shippingInfo, discounts, payable,
                BigDecimal.ZERO, ShippingMethod.HOME_DELIVERY, now);
    }

    /**
     * 建立帶運費的訂單。
     *
     * @param shippingFee 已扣掉免運折抵的<b>實收</b>運費。
     *                    它<b>不進</b> {@code payable}——那條恆等式
     *                    （各行實付加總 == 折後應付）是退款按行退的基礎，
     *                    而運費不分攤到行（ADR-0019 決策 1）
     */
    public static Order place(OrderNo orderNo, Long userId, String requestId,
                              List<OrderLine> lines, ShippingInfo shippingInfo,
                              List<OrderDiscount> discounts, BigDecimal payable,
                              BigDecimal shippingFee, ShippingMethod shippingMethod, Instant now) {
        Objects.requireNonNull(shippingInfo, "一般訂單必須有收貨資訊");

        // 三個數字必須自洽：總額 = 各行實付的加總，且 = 小計 − 折扣加總。
        //
        // 這是折扣功能唯一會安靜出錯的地方。分攤算錯不會拋例外、不會被上限擋下，
        // 只會讓退款按一組數字算、收款按另一組——半年後對帳才發現。
        // 在建立訂單的當下驗一次，是最便宜的攔截點。
        BigDecimal allocated = lines.stream()
                .map(OrderLine::allocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.compareTo(payable) != 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "各行實付加總 %s 與折後應付 %s 不符".formatted(allocated, payable));
        }
        // **只算商品折抵。** 運費折抵折的是另一筆錢（ADR-0019 決策 1），
        // 放進這條等式會讓它失效——而這條等式正是退款按行退的基礎
        BigDecimal discountTotal = discounts.stream()
                .filter(discount -> !discount.appliesToShipping())
                .map(OrderDiscount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sumOf(lines).subtract(discountTotal).compareTo(payable) != 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "小計減折扣與折後應付不符");
        }

        Order order = new Order(orderNo, userId, OrderChannel.NORMAL, requestId,
                lines, payable, shippingInfo, OrderStatus.PENDING_PAYMENT,
                now, null, null, discounts, 0L, shippingFee, shippingMethod);
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
                activity.skuId(),
                activity.productName(),
                activity.seckillPrice(),
                quantity,
                activity.id());

        // 秒殺沒有收貨資訊：那條通道的下單當下不收集地址（見 shippingInfo 欄位說明）
        Order order = new Order(orderNo, userId, OrderChannel.SECKILL, requestId,
                List.of(line), line.subtotal(), null, OrderStatus.PENDING_PAYMENT,
                // 秒殺通道沒有運費：它連地址都不收，自然算不出運費。
                // 真要收的話是另一條路徑（下單後補地址），那是另一個決定
                now, null, null, List.of(), 0L, BigDecimal.ZERO, ShippingMethod.HOME_DELIVERY);
        order.registerEvent(OrderCreatedEvent.of(order, now));
        return order;
    }

    /**
     * 從持久化狀態重建，<b>不</b>產生領域事件。
     *
     * <p>與建立方法明確分離，避免 repository 載入既有訂單時誤觸發事件。
     */
    public static Order restore(OrderNo orderNo, Long userId, OrderChannel channel, String requestId,
                                List<OrderLine> lines, BigDecimal totalAmount,
                                ShippingInfo shippingInfo, OrderStatus status,
                                Instant createdAt, Instant paidAt, String closeReason, long version) {
        return restore(orderNo, userId, channel, requestId, lines, totalAmount, shippingInfo,
                status, createdAt, paidAt, closeReason, List.of(), version);
    }

    public static Order restore(OrderNo orderNo, Long userId, OrderChannel channel, String requestId,
                                List<OrderLine> lines, BigDecimal totalAmount,
                                ShippingInfo shippingInfo, OrderStatus status,
                                Instant createdAt, Instant paidAt, String closeReason,
                                List<OrderDiscount> discounts, long version) {
        return restore(orderNo, userId, channel, requestId, lines, totalAmount, shippingInfo,
                status, createdAt, paidAt, closeReason, discounts, version,
                BigDecimal.ZERO, ShippingMethod.HOME_DELIVERY);
    }

    public static Order restore(OrderNo orderNo, Long userId, OrderChannel channel, String requestId,
                                List<OrderLine> lines, BigDecimal totalAmount,
                                ShippingInfo shippingInfo, OrderStatus status,
                                Instant createdAt, Instant paidAt, String closeReason,
                                List<OrderDiscount> discounts, long version,
                                BigDecimal shippingFee, ShippingMethod shippingMethod) {
        return new Order(orderNo, userId, channel, requestId, lines, totalAmount,
                shippingInfo, status, createdAt, paidAt, closeReason, discounts, version,
                shippingFee, shippingMethod);
    }

    /** 付款成功。 */
    public void pay(Instant paidAt) {
        transitionTo(OrderStatus.PAID);
        this.paidAt = paidAt;
        registerEvent(OrderPaidEvent.of(this, paidAt));
    }

    /**
     * 出貨。
     *
     * <p>這是<b>不可逆的分水嶺</b>：出貨前買家可自由取消（退錢退庫存都來得及），
     * 出貨後必須走退貨流程——貨在路上，庫存不能直接退回可售池。
     * 狀態機用 {@code SHIPPED} 不允許轉回 {@code CANCELLED} 把這件事釘死。
     */
    public void ship(Instant shippedAt) {
        transitionTo(OrderStatus.SHIPPED);
        registerEvent(OrderShippedEvent.of(this, shippedAt));
    }

    /** 送達，訂單完成。 */
    public void complete(Instant completedAt) {
        transitionTo(OrderStatus.COMPLETED);
        registerEvent(OrderCompletedEvent.of(this, completedAt));
    }

    /**
     * 標記為全額退款完成。
     *
     * <p><b>刻意不發出任何事件。</b>庫存回補與退款都已經由退貨流程處理過了
     * （ADR-0011），這裡只是把訂單的狀態校正到與事實一致。
     * 若發出 {@code OrderCancelledEvent}，補償服務會再退一次庫存——
     * 那是超賣。
     *
     * <p>由應用層在確認「所有訂單行都退完」之後呼叫。
     * 訂單自己不知道有幾張退貨單，也不該知道。
     */
    public void markFullyRefunded(String reason, Instant now) {
        transitionTo(OrderStatus.REFUNDED);
        this.closeReason = reason;
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

    /** 收貨資訊快照；秒殺訂單為 {@code null}。 */
    public ShippingInfo shippingInfo() {
        return shippingInfo;
    }

    public BigDecimal totalAmount() {
        return totalAmount;
    }

    /** 運費。已扣掉免運折抵，是實際要收的那個數字。 */
    public BigDecimal shippingFee() {
        return shippingFee;
    }

    public ShippingMethod shippingMethod() {
        return shippingMethod;
    }

    /**
     * 這張訂單總共要付多少：商品折後 + 運費。
     *
     * <p><b>付款與退款上限用這個，不是 {@link #totalAmount}。</b>
     * 用 totalAmount 的話運費就收不到，而且<b>沒有任何東西會發現</b>——
     * 付款成功、訂單完成、貨也寄了，只有月底對帳時發現每一單都少收幾十元
     * （ADR-0019 決策 2）。
     */
    public BigDecimal payableAmount() {
        return totalAmount.add(shippingFee);
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

    /** 已套用的折扣明細。 */
    public List<OrderDiscount> discounts() {
        return discounts;
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
