package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 訂單的持久化模型。 */
@Entity
@Table(
        name = "orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_order_no", columnNames = "order_no"),
                @UniqueConstraint(name = "uk_request_id", columnNames = "request_id")
        },
        indexes = {
                // 逾期關單排程的查詢條件：status + created_at
                @Index(name = "idx_status_created", columnList = "status,created_at"),
                @Index(name = "idx_user_created", columnList = "user_id,created_at")
        })
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 64, updatable = false)
    private String orderNo;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** NORMAL / SECKILL。僅供追溯與報表，不用於控制流程。 */
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private String channel;

    @Column(name = "request_id", nullable = false, length = 64, updatable = false)
    private String requestId;

    /** 由訂單行加總，建立後不可變——這是反正規化能成立的前提。 */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    /** 運費。<b>不計入 total_amount</b>（ADR-0019 決策 1）—— 那條恆等式是退款按行退的基礎，而運費不分攤到行。 */
    @Column(name = "shipping_fee", nullable = false, precision = 12, scale = 2,
            updatable = false)
    private BigDecimal shippingFee;

    /** 買家備註。與金額同樣 {@code updatable = false}——它是出貨依據。 */
    @Column(name = "buyer_note", length = 200, updatable = false)
    private String buyerNote;

    /**
     * 內部註記。
     *
     * <p><b>與買家備註分成兩欄，不可共用。</b> 共用的話，客服寫的
     * 「疑似黃牛」會出現在買家的訂單頁上——那不是靠「記得別亂寫」能避免的。
     * 這一欄永遠不會出現在買家看得到的端點上。
     */
    @Column(name = "staff_note", length = 500)
    private String staffNote;

    @Column(name = "shipping_method", nullable = false, length = 24, updatable = false)
    private String shippingMethod;

    /** 收貨資訊快照，全部 {@code updatable = false}。 */
    @Column(name = "ship_recipient", length = 32, updatable = false)
    private String shipRecipient;

    @Column(name = "ship_phone", length = 24, updatable = false)
    private String shipPhone;

    @Column(name = "ship_postal_code", length = 8, updatable = false)
    private String shipPostalCode;

    @Column(name = "ship_region", length = 32, updatable = false)
    private String shipRegion;

    @Column(name = "ship_district", length = 32, updatable = false)
    private String shipDistrict;

    @Column(name = "ship_street", length = 128, updatable = false)
    private String shipStreet;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "close_reason", length = 128)
    private String closeReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** {@code @OrderColumn} 保證行的順序穩定——否則每次查詢的排列可能不同。 */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderColumn(name = "line_no")
    private List<OrderLineEntity> lines = new ArrayList<>();

    protected OrderEntity() {
        // JPA 專用
    }

    public OrderEntity(String orderNo, Long userId, String channel, String requestId,
                       BigDecimal totalAmount, String status, Instant createdAt,
                       Instant paidAt, String closeReason,
                       BigDecimal shippingFee, String shippingMethod, String buyerNote) {
        this.buyerNote = buyerNote;
        this.shippingFee = shippingFee;
        this.shippingMethod = shippingMethod;
        this.orderNo = orderNo;
        this.userId = userId;
        this.channel = channel;
        this.requestId = requestId;
        this.totalAmount = totalAmount;
        this.status = status;
        this.createdAt = createdAt;
        this.paidAt = paidAt;
        this.closeReason = closeReason;
    }

    public String buyerNote() {
        return buyerNote;
    }

    public String staffNote() {
        return staffNote;
    }

    /** 內部註記可以改：它是營運的工作筆記，不是訂單事實的一部分。 */
    public void updateStaffNote(String note) {
        this.staffNote = note;
    }

    /** 寫入收貨資訊快照。 */
    public void applyShippingInfo(String recipient, String phone, String postalCode,
                                  String region, String district, String street) {
        this.shipRecipient = recipient;
        this.shipPhone = phone;
        this.shipPostalCode = postalCode;
        this.shipRegion = region;
        this.shipDistrict = district;
        this.shipStreet = street;
    }

    public String getShipRecipient() {
        return shipRecipient;
    }

    public String getShipPhone() {
        return shipPhone;
    }

    public String getShipPostalCode() {
        return shipPostalCode;
    }

    public String getShipRegion() {
        return shipRegion;
    }

    public String getShipDistrict() {
        return shipDistrict;
    }

    public String getShipStreet() {
        return shipStreet;
    }

    /** 加入訂單行並維護雙向關聯——只設一邊會讓 JPA 寫不出外鍵。 */
    /** 折扣明細。用 {@code @OrderBy("id")} 而不是 {@code @OrderColumn}： 折扣沒有「使用者排的順序」這種語意，只需要每次查詢排列相同。 */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("id")
    private List<OrderDiscountEntity> discounts = new ArrayList<>();

    public void addDiscount(OrderDiscountEntity discount) {
        discounts.add(discount);
        discount.attachTo(this);
    }

    public List<OrderDiscountEntity> getDiscounts() {
        return discounts;
    }

    public void addLine(OrderLineEntity line) {
        lines.add(line);
        line.attachTo(this);
    }

    /** 狀態流轉時的欄位更新；其餘欄位由 {@code updatable = false} 鎖死。 */
    public void applyStateChange(String status, Instant paidAt, String closeReason) {
        this.status = status;
        this.paidAt = paidAt;
        this.closeReason = closeReason;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public String getChannel() {
        return channel;
    }

    public String getRequestId() {
        return requestId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public String getShippingMethod() {
        return shippingMethod;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public long getVersion() {
        return version;
    }

    public List<OrderLineEntity> getLines() {
        return lines;
    }
}
