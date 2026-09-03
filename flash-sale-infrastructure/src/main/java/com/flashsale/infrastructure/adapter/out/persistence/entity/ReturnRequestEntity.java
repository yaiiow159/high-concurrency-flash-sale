package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 退貨單。
 *
 * <p>{@code @OrderBy("skuId")} 讓退貨行的順序穩定。不用 {@code @OrderColumn} 是因為
 * 退貨行沒有「使用者排的順序」這種語意——它只需要每次查詢排列相同，
 * 而多一個序號欄位就多一個要維護的東西。
 */
@Entity
@Table(name = "return_request")
public class ReturnRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_no", nullable = false, length = 64, updatable = false)
    private String returnNo;

    @Column(name = "order_no", nullable = false, length = 64, updatable = false)
    private String orderNo;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "reason", nullable = false, length = 24, updatable = false)
    private String reason;

    @Column(name = "reason_detail", length = 512, updatable = false)
    private String reasonDetail;

    /** 由訂單狀態決定，建立後不可變——它決定了退款前必不必須先驗收。 */
    @Column(name = "requires_goods_return", nullable = false, updatable = false)
    private boolean requiresGoodsReturn;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "review_note", length = 512)
    private String reviewNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("skuId")
    private List<ReturnLineEntity> lines = new ArrayList<>();

    protected ReturnRequestEntity() {
        // JPA 專用
    }

    public ReturnRequestEntity(String returnNo, String orderNo, Long userId, String reason,
                               String reasonDetail, boolean requiresGoodsReturn, String status,
                               Instant createdAt) {
        this.returnNo = returnNo;
        this.orderNo = orderNo;
        this.userId = userId;
        this.reason = reason;
        this.reasonDetail = reasonDetail;
        this.requiresGoodsReturn = requiresGoodsReturn;
        this.status = status;
        this.createdAt = createdAt;
    }

    public void addLine(ReturnLineEntity line) {
        lines.add(line);
        line.attachTo(this);
    }

    /**
     * 套用狀態變更。
     *
     * <p>驗收結果（{@code restockable}）也在這裡一併寫入——
     * 它與 {@code RECEIVED} 是同一個動作的兩個面向，分開寫會出現
     * 「已驗收但沒有驗收結果」的中間狀態。
     */
    public void applyStateChange(String status, String reviewNote, Instant reviewedAt,
                                 Instant receivedAt, Instant refundedAt,
                                 Map<Long, Boolean> restockableBySku) {
        this.status = status;
        this.reviewNote = reviewNote;
        this.reviewedAt = reviewedAt;
        this.receivedAt = receivedAt;
        this.refundedAt = refundedAt;
        // 以 skuId 對應而不是按位置：兩邊的排序規則各自演化時，
        // 按位置寫入會把 A 的驗收結果套到 B 身上，而且完全不會報錯
        for (ReturnLineEntity line : lines) {
            Boolean decision = restockableBySku.get(line.getSkuId());
            if (decision != null) {
                line.applyInspection(decision);
            }
        }
    }

    public Long getId() {
        return id;
    }

    public String getReturnNo() {
        return returnNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public String getReason() {
        return reason;
    }

    public String getReasonDetail() {
        return reasonDetail;
    }

    public boolean isRequiresGoodsReturn() {
        return requiresGoodsReturn;
    }

    public String getStatus() {
        return status;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<ReturnLineEntity> getLines() {
        return lines;
    }
}
