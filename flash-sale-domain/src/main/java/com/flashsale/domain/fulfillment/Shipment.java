package com.flashsale.domain.fulfillment;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 出貨單聚合根。
 *
 * <h2>它<b>不</b>持有收貨地址</h2>
 *
 * <p>地址快照已經在訂單上了（見 {@code ShippingInfo}）。出貨單再存一份，
 * 就會出現「訂單寫著寄台北、出貨單寫著寄高雄」這種沒有人能仲裁的狀態——
 * 而那兩份資料理論上永遠應該相同。
 *
 * <p>需要地址時由應用層從訂單取。多一次查詢，換掉一整類對不起來的資料。
 *
 * <h2>物流細節在這裡，不在訂單上</h2>
 *
 * <p>承運商、物流單號、配送失敗原因、重新派送次數——這些是 Fulfillment 的內部狀態。
 * 訂單只記「已出貨」這個里程碑，因為那才是<b>改變買家能做什麼</b>的轉折。
 *
 * <p>一張訂單目前只有一張出貨單。分批出貨（一張訂單拆多個包裹）需要
 * 訂單行與出貨單的多對多關係，那是另一個決策，不在這一步的範圍內；
 * 但 {@code orderNo} 沒有唯一約束，正是為了讓那一步不必改表。
 */
public final class Shipment {

    private static final int MAX_TRACKING_LENGTH = 64;
    private static final int MAX_REASON_LENGTH = 256;

    private final Long id;
    private final ShipmentNo shipmentNo;
    private final String orderNo;
    private final Long userId;
    private final Instant createdAt;

    private Carrier carrier;
    private String trackingNumber;
    private ShipmentStatus status;
    private String failureReason;
    private int dispatchCount;
    private Instant shippedAt;
    private Instant deliveredAt;

    private Shipment(Long id, ShipmentNo shipmentNo, String orderNo, Long userId,
                     Carrier carrier, String trackingNumber, ShipmentStatus status,
                     String failureReason, int dispatchCount, Instant createdAt,
                     Instant shippedAt, Instant deliveredAt) {
        this.id = id;
        this.shipmentNo = Objects.requireNonNull(shipmentNo, "shipmentNo 不可為 null");
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo 不可為 null");
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.status = Objects.requireNonNull(status, "status 不可為 null");
        this.failureReason = failureReason;
        this.dispatchCount = dispatchCount;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        this.shippedAt = shippedAt;
        this.deliveredAt = deliveredAt;
    }

    /** 訂單付款後建立，等待揀貨。 */
    public static Shipment prepare(ShipmentNo shipmentNo, String orderNo,
                                   Long userId, Instant now) {
        return new Shipment(null, shipmentNo, orderNo, userId, null, null,
                ShipmentStatus.READY, null, 0, now, null, null);
    }

    public static Shipment restore(Long id, ShipmentNo shipmentNo, String orderNo, Long userId,
                                   Carrier carrier, String trackingNumber, ShipmentStatus status,
                                   String failureReason, int dispatchCount, Instant createdAt,
                                   Instant shippedAt, Instant deliveredAt) {
        return new Shipment(Objects.requireNonNull(id, "重建時 id 不可為 null"), shipmentNo,
                orderNo, userId, carrier, trackingNumber, status, failureReason,
                dispatchCount, createdAt, shippedAt, deliveredAt);
    }

    /**
     * 交付承運商。
     *
     * <p>物流單號<b>必填</b>：沒有單號的出貨等於沒辦法追蹤，
     * 而使用者問「我的東西到哪了」時只能回答「不知道」。
     * 這種狀態在系統裡不該存在得下去。
     */
    public void dispatch(Carrier carrier, String trackingNumber, Instant now) {
        transitionTo(ShipmentStatus.IN_TRANSIT);
        this.carrier = Objects.requireNonNull(carrier, "承運商不可為 null");
        this.trackingNumber = requireTracking(trackingNumber);
        this.failureReason = null;
        this.dispatchCount++;
        // 第一次出貨才記時間。重新派送不覆寫——
        // 「這張單什麼時候第一次出貨」是出貨時效的分母，被覆寫就再也算不出來
        if (this.shippedAt == null) {
            this.shippedAt = now;
        }
    }

    public void deliver(Instant now) {
        transitionTo(ShipmentStatus.DELIVERED);
        this.deliveredAt = now;
        this.failureReason = null;
    }

    /**
     * 配送失敗。
     *
     * <p>不是終態——後續幾乎都是重新派送而非取消。理由見 {@link ShipmentStatus}。
     */
    public void markFailed(String reason) {
        transitionTo(ShipmentStatus.FAILED);
        this.failureReason = requireReason(reason);
    }

    /** 出貨前取消（訂單被取消時連帶）。 */
    public void cancel(String reason) {
        transitionTo(ShipmentStatus.CANCELLED);
        this.failureReason = requireReason(reason);
    }

    /**
     * 這張出貨單是否還能被訂單端直接取消。
     *
     * <p>貨一旦離開倉庫就不行了——那時要退錢必須走退貨流程，
     * 因為庫存不能直接退回可售池（東西還在路上）。
     */
    public boolean isCancellable() {
        return !status.hasLeftWarehouse() && !status.isFinal();
    }

    public void requireOwnedBy(Long expectedUserId) {
        if (!Objects.equals(userId, expectedUserId)) {
            // 與地址簿同理：回「不存在」而非「無權限」，
            // 否則攻擊者能靠窮舉列舉出系統裡有多少出貨單
            throw new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND);
        }
    }

    private void transitionTo(ShipmentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.ILLEGAL_SHIPMENT_STATE_TRANSITION,
                    "出貨單 %s 無法從 %s 轉為 %s".formatted(shipmentNo.value(), status, target));
        }
        this.status = target;
    }

    private static String requireTracking(String trackingNumber) {
        String trimmed = trackingNumber == null ? "" : trackingNumber.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "物流單號不可為空");
        }
        if (trimmed.length() > MAX_TRACKING_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "物流單號不可超過 %d 字".formatted(MAX_TRACKING_LENGTH));
        }
        return trimmed;
    }

    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "原因不可為空");
        }
        return trimmed.length() > MAX_REASON_LENGTH
                ? trimmed.substring(0, MAX_REASON_LENGTH)
                : trimmed;
    }

    public Long id() {
        return id;
    }

    public ShipmentNo shipmentNo() {
        return shipmentNo;
    }

    public String orderNo() {
        return orderNo;
    }

    public Long userId() {
        return userId;
    }

    public Carrier carrier() {
        return carrier;
    }

    public String trackingNumber() {
        return trackingNumber;
    }

    public ShipmentStatus status() {
        return status;
    }

    public String failureReason() {
        return failureReason;
    }

    /** 派送次數。大於 1 代表曾經配送失敗後重送，是物流品質的指標。 */
    public int dispatchCount() {
        return dispatchCount;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant shippedAt() {
        return shippedAt;
    }

    public Instant deliveredAt() {
        return deliveredAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Shipment other && Objects.equals(shipmentNo, other.shipmentNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shipmentNo);
    }

    @Override
    public String toString() {
        return "Shipment{no=%s, orderNo=%s, status=%s, dispatches=%d}"
                .formatted(shipmentNo.value(), orderNo, status, dispatchCount);
    }
}
