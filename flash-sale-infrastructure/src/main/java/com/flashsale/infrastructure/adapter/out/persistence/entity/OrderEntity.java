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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 訂單的持久化模型。
 *
 * <p><b>{@code request_id} 的唯一約束是全系統防重複下單的最後一道防線。</b>
 * Redis 冪等、MQ 冪等都可能因為鍵過期或消費組重置而失效，
 * 唯有資料庫的唯一索引是永久且無條件成立的。
 *
 * <p>訂單行採 {@code CascadeType.ALL} 且 {@code FetchType.LAZY}：
 * 行是訂單聚合的一部分，生命週期完全跟隨訂單；
 * 而列表查詢只需要訂單本身，不該無條件把行一起撈出來（N+1 的來源）。
 */
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

    /**
     * 收貨資訊快照，全部 {@code updatable = false}。
     *
     * <p>與金額同理（見 ADR-0007 與 CLAUDE.md 規則 7-2）：訂單記錄的是
     * 「當初要寄到哪裡」，不是「這個使用者現在住哪」。使用者搬家改了地址簿，
     * 已成立的訂單不能跟著變——那是出貨紀錄與客訴處理的依據。
     *
     * <p>秒殺訂單建立當下沒有地址，因此全部可為 null。
     */
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
                       Instant paidAt, String closeReason) {
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

    /**
     * 寫入收貨資訊快照。
     *
     * <p>只在建立時呼叫一次。之後即使有人再呼叫，JPA 也不會把值寫進資料庫——
     * 那些欄位是 {@code updatable = false}。這個「呼叫了卻沒效果」的行為
     * 是刻意的最後防線：它讓「訂單建立後改地址」失敗得安靜但無害，
     * 而不是安靜地成功。
     */
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
