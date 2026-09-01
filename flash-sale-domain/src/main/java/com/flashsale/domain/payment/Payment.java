package com.flashsale.domain.payment;

import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.payment.event.PaymentRefundRequiredEvent;
import com.flashsale.domain.payment.event.PaymentSucceededEvent;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 付款聚合根。
 *
 * <p><b>金額在建立時從訂單複製，之後不可變。</b>
 * 若付款金額能被改動，「應付」與「實付」就會失去對應關係，
 * 對帳時無從判斷差額是折扣、退款、還是有人動了手腳。
 *
 * <p><b>冪等由狀態機保證</b>：真實金流閘道會重送回調（有些會送三、四次），
 * 重複套用同一筆成功結果時，{@code SUCCEEDED → SUCCEEDED} 不是合法轉移，
 * 呼叫端據此判定為重複而略過，不需要額外的去重表。
 */
public final class Payment {

    private final Long id;
    private final PaymentNo paymentNo;
    private final OrderNo orderNo;
    private final Long userId;
    private final BigDecimal amount;
    private final Instant createdAt;

    private PaymentStatus status;
    private String gatewayTransactionId;
    private Instant paidAt;
    private String failureReason;
    private final long version;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private Payment(Long id, PaymentNo paymentNo, OrderNo orderNo, Long userId, BigDecimal amount,
                    PaymentStatus status, String gatewayTransactionId, Instant createdAt,
                    Instant paidAt, String failureReason, long version) {
        this.id = id;
        this.paymentNo = Objects.requireNonNull(paymentNo, "paymentNo 不可為 null");
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo 不可為 null");
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.amount = requirePositive(amount);
        this.status = Objects.requireNonNull(status, "status 不可為 null");
        this.gatewayTransactionId = gatewayTransactionId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        this.paidAt = paidAt;
        this.failureReason = failureReason;
        this.version = version;
    }

    /** 發起付款。金額由訂單決定，不接受呼叫端傳入——否則前端就能自己決定要付多少。 */
    public static Payment initiate(PaymentNo paymentNo, OrderNo orderNo, Long userId,
                                   BigDecimal amount, Instant now) {
        return new Payment(null, paymentNo, orderNo, userId, amount,
                PaymentStatus.PENDING, null, now, null, null, 0L);
    }

    public static Payment restore(Long id, PaymentNo paymentNo, OrderNo orderNo, Long userId,
                                  BigDecimal amount, PaymentStatus status, String gatewayTransactionId,
                                  Instant createdAt, Instant paidAt, String failureReason, long version) {
        return new Payment(id, paymentNo, orderNo, userId, amount, status,
                gatewayTransactionId, createdAt, paidAt, failureReason, version);
    }

    /**
     * 標記收款成功。
     *
     * <p>此時錢已經真的收了。後續訂單能否入帳是另一件事——
     * 即使訂單已被關閉，這一步仍必須先如實記錄，
     * 否則帳上會顯示「沒收到錢」而現實是收到的。
     */
    public void markSucceeded(String gatewayTransactionId, Instant paidAt) {
        transitionTo(PaymentStatus.SUCCEEDED);
        this.gatewayTransactionId = Objects.requireNonNull(gatewayTransactionId,
                "成功的付款必須帶閘道交易編號，否則出事時無從對帳");
        this.paidAt = paidAt;
        registerEvent(PaymentSucceededEvent.of(this, paidAt));
    }

    public void markFailed(String reason, Instant now) {
        transitionTo(PaymentStatus.FAILED);
        this.failureReason = reason;
    }

    /**
     * 收款成功但訂單已無法入帳，轉為待退款。
     *
     * <p>唯一的成因是「付款完成時逾時關單排程已先一步取消訂單」。
     * 這是最終一致系統中無法完全避免的競態——只能誠實記錄並補償。
     */
    public void markRefundRequired(String reason, Instant now) {
        transitionTo(PaymentStatus.REFUND_PENDING);
        this.failureReason = reason;
        registerEvent(PaymentRefundRequiredEvent.of(this, reason, now));
    }

    public void markRefunded(Instant now) {
        transitionTo(PaymentStatus.REFUNDED);
    }

    /** 失敗後重新發起，沿用同一張付款單。 */
    public void retry(Instant now) {
        transitionTo(PaymentStatus.PENDING);
        this.failureReason = null;
        this.gatewayTransactionId = null;
    }

    /**
     * 此付款是否已被終結，重複的閘道回調應直接略過。
     *
     * <p>真實金流閘道會重送回調，這是常態而非異常。
     */
    public boolean isAlreadySettled() {
        return status != PaymentStatus.PENDING;
    }

    public boolean belongsTo(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }

    private void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.ILLEGAL_PAYMENT_STATE_TRANSITION,
                    "付款 %s 無法從 %s 轉為 %s".formatted(paymentNo, status, target));
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

    private static BigDecimal requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "付款金額必須大於 0");
        }
        return value;
    }

    public Long id() {
        return id;
    }

    public PaymentNo paymentNo() {
        return paymentNo;
    }

    public OrderNo orderNo() {
        return orderNo;
    }

    public Long userId() {
        return userId;
    }

    public BigDecimal amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status;
    }

    public String gatewayTransactionId() {
        return gatewayTransactionId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public String failureReason() {
        return failureReason;
    }

    public long version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Payment other && Objects.equals(paymentNo, other.paymentNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(paymentNo);
    }

    @Override
    public String toString() {
        return "Payment{paymentNo=%s, orderNo=%s, status=%s, amount=%s}"
                .formatted(paymentNo, orderNo, status, amount);
    }
}
