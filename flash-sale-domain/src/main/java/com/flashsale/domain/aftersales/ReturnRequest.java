package com.flashsale.domain.aftersales;

import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 退貨單聚合根（ADR-0011）。
 *
 * <p>一張訂單可以有<b>多張</b>退貨單——先退一件、兩週後再退另一件是常見情境。
 * 因此它是獨立聚合根而非訂單上的欄位；壓成欄位等於強加
 * 「同時只能有一張退貨單」這個沒有業務理由的限制。
 *
 * <h2>它引用訂單，但不修改訂單</h2>
 *
 * <p>訂單只在<b>全額退完</b>時轉為 {@code REFUNDED}，而那由應用層在
 * 比對過所有退貨單之後決定。退貨單自己不知道「全部」是多少，
 * 也不該知道——它只認得自己這一張。
 *
 * <h2>{@code requiresGoodsReturn} 由聚合根決定，不由呼叫端傳入</h2>
 *
 * <p>未出貨的訂單沒有貨要寄回。但若讓呼叫端自己宣告，
 * 「已出貨卻宣稱免寄回」就是一個免費拿貨的漏洞。
 */
public final class ReturnRequest {

    private final Long id;
    private final ReturnNo returnNo;
    private final OrderNo orderNo;
    private final Long userId;
    /** 冪等鍵。由呼叫端在送出前產生並在重試間保留，配合唯一索引擋下重複申請。 */
    private final String requestId;
    private final ReturnReason reason;
    private final String reasonDetail;
    private final boolean requiresGoodsReturn;
    private final Instant createdAt;

    private List<ReturnLine> lines;
    private ReturnStatus status;
    private String reviewNote;
    private Instant reviewedAt;
    private Instant receivedAt;
    private Instant refundedAt;
    private final long version;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private ReturnRequest(Long id, ReturnNo returnNo, OrderNo orderNo, Long userId,
                          String requestId, ReturnReason reason, String reasonDetail,
                          boolean requiresGoodsReturn,
                          List<ReturnLine> lines, ReturnStatus status, String reviewNote,
                          Instant createdAt, Instant reviewedAt, Instant receivedAt,
                          Instant refundedAt, long version) {
        this.id = id;
        this.returnNo = Objects.requireNonNull(returnNo, "returnNo 不可為 null");
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo 不可為 null");
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.requestId = requireRequestId(requestId);
        this.reason = Objects.requireNonNull(reason, "reason 不可為 null");
        this.reasonDetail = reasonDetail;
        this.requiresGoodsReturn = requiresGoodsReturn;
        this.lines = requireNonEmpty(lines);
        this.status = Objects.requireNonNull(status, "status 不可為 null");
        this.reviewNote = reviewNote;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        this.reviewedAt = reviewedAt;
        this.receivedAt = receivedAt;
        this.refundedAt = refundedAt;
        this.version = version;
    }

    /**
     * 建立退貨申請。
     *
     * @param requiresGoodsReturn 是否需要買家寄回。由應用層依訂單狀態判定
     *                            （{@code SHIPPED}／{@code COMPLETED} 為 true），
     *                            不接受買家自己指定
     */
    public static ReturnRequest open(ReturnNo returnNo, OrderNo orderNo, Long userId,
                                     String requestId, List<ReturnLine> lines, ReturnReason reason,
                                     String reasonDetail, boolean requiresGoodsReturn,
                                     Instant now) {
        return new ReturnRequest(null, returnNo, orderNo, userId, requestId, reason, reasonDetail,
                requiresGoodsReturn, lines, ReturnStatus.REQUESTED, null,
                now, null, null, null, 0L);
    }

    public static ReturnRequest restore(Long id, ReturnNo returnNo, OrderNo orderNo, Long userId,
                                        String requestId, ReturnReason reason, String reasonDetail,
                                        boolean requiresGoodsReturn, List<ReturnLine> lines,
                                        ReturnStatus status, String reviewNote, Instant createdAt,
                                        Instant reviewedAt, Instant receivedAt, Instant refundedAt,
                                        long version) {
        return new ReturnRequest(id, returnNo, orderNo, userId, requestId, reason, reasonDetail,
                requiresGoodsReturn, lines, status, reviewNote, createdAt,
                reviewedAt, receivedAt, refundedAt, version);
    }

    public void approve(String note, Instant now) {
        transitionTo(ReturnStatus.APPROVED);
        this.reviewNote = note;
        this.reviewedAt = now;
    }

    public void reject(String note, Instant now) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "駁回必須說明理由");
        }
        transitionTo(ReturnStatus.REJECTED);
        this.reviewNote = note;
        this.reviewedAt = now;
    }

    /** 買家自行撤回。貨一旦收下就不能再撤——那會讓買家既沒錢也沒貨。 */
    public void cancel(Instant now) {
        transitionTo(ReturnStatus.CANCELLED);
        this.reviewedAt = now;
    }

    /**
     * 收到退回品並完成驗收。
     *
     * <p>逐行判定是否可再售。<b>驗收結果必須涵蓋每一行</b>——
     * 漏掉一行時預設為「可再售」是危險的預設值：
     * 那會把毀損品的成本靜靜地算成庫存。
     *
     * @param restockDecisions skuId → 是否可再售
     */
    public void receive(Map<Long, Boolean> restockDecisions, Instant now) {
        if (!requiresGoodsReturn) {
            throw new BusinessException(ErrorCode.ILLEGAL_RETURN_STATE_TRANSITION,
                    "退貨單 %s 免寄回，沒有可驗收的退回品".formatted(returnNo));
        }
        transitionTo(ReturnStatus.RECEIVED);
        this.lines = lines.stream()
                .map(line -> {
                    Boolean decision = restockDecisions.get(line.skuId());
                    if (decision == null) {
                        throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                                "驗收結果缺少 SKU %d，不可預設為可再售".formatted(line.skuId()));
                    }
                    return line.inspected(decision);
                })
                .toList();
        this.receivedAt = now;
    }

    /**
     * 標記退款完成。
     *
     * <p>發出 {@link RefundRequestedEvent} 進 Outbox，由消費端呼叫金流——
     * 遠端呼叫不留在交易裡（ADR-0004、ADR-0011 決策 8）。
     *
     * <p>免寄回時可直接從 {@code APPROVED} 進來；需寄回時必須先經過驗收，
     * 否則就是「貨還沒回來就把錢退掉」。
     */
    public void markRefunded(Instant now) {
        if (requiresGoodsReturn && status != ReturnStatus.RECEIVED) {
            throw new BusinessException(ErrorCode.ILLEGAL_RETURN_STATE_TRANSITION,
                    "退貨單 %s 需寄回，必須先驗收才能退款".formatted(returnNo));
        }
        transitionTo(ReturnStatus.REFUNDED);
        this.refundedAt = now;
        registerEvent(RefundRequestedEvent.of(this, now));
    }

    /** 退款總額。由退貨行的快照單價推導，不獨立儲存——避免出現兩個真實來源。 */
    public BigDecimal refundAmount() {
        return lines.stream()
                .map(ReturnLine::refundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 需要回補到一般庫存的行。免寄回時全數回補——貨從未離開倉庫。 */
    public List<ReturnLine> restockableLines() {
        return requiresGoodsReturn
                ? lines.stream().filter(ReturnLine::shouldRestock).toList()
                : List.copyOf(lines);
    }

    /** 某個 SKU 在這張退貨單上的數量；不在單上為 0。 */
    public int quantityOf(Long skuId) {
        return lines.stream()
                .filter(line -> line.skuId().equals(skuId))
                .mapToInt(ReturnLine::quantity)
                .sum();
    }

    public boolean belongsTo(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }

    private void transitionTo(ReturnStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.ILLEGAL_RETURN_STATE_TRANSITION,
                    "退貨單 %s 無法從 %s 轉為 %s".formatted(returnNo, status, target));
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

    private static String requireRequestId(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "requestId 不可為空");
        }
        return candidate;
    }

    private static List<ReturnLine> requireNonEmpty(List<ReturnLine> candidate) {
        if (candidate == null || candidate.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "退貨單至少要有一個品項");
        }
        return List.copyOf(candidate);
    }

    public Long id() {
        return id;
    }

    public ReturnNo returnNo() {
        return returnNo;
    }

    public OrderNo orderNo() {
        return orderNo;
    }

    public Long userId() {
        return userId;
    }

    public String requestId() {
        return requestId;
    }

    public ReturnReason reason() {
        return reason;
    }

    public String reasonDetail() {
        return reasonDetail;
    }

    public boolean requiresGoodsReturn() {
        return requiresGoodsReturn;
    }

    public List<ReturnLine> lines() {
        return lines;
    }

    public ReturnStatus status() {
        return status;
    }

    public String reviewNote() {
        return reviewNote;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant reviewedAt() {
        return reviewedAt;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Instant refundedAt() {
        return refundedAt;
    }

    public long version() {
        return version;
    }
}
