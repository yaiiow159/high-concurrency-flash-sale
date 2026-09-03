package com.flashsale.domain.aftersales.event;

import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.shared.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 退款已核可，請執行。
 *
 * <p>經 Outbox 投遞，消費端負責兩件事：呼叫金流退款、回補可再售的庫存。
 * 遠端呼叫不留在交易裡（ADR-0011 決策 8）——
 * 退款這條路上，「不知道成功了沒」等於「不知道錢送出去了沒」。
 *
 * <p><b>消費端必須冪等</b>：Outbox 是至少一次語意，
 * 而這個事件重複執行的代價是把錢送出去兩次。
 *
 * <p>事件自帶要回補的 SKU 與數量，消費端不必回頭讀退貨單——
 * 那是一次不必要的往返，也讓事件在稽核時無法自我解釋。
 *
 * @param restockLines 驗收後判定可再售的品項；免寄回時為全部品項
 */
public record RefundRequestedEvent(
        String eventId,
        String returnNo,
        String orderNo,
        Long userId,
        BigDecimal refundAmount,
        List<RestockLine> restockLines,
        Instant occurredAt
) implements DomainEvent {

    public static final String TYPE = "refund.requested";

    /** 要回補到<b>一般庫存</b>的品項。絕不回秒殺池（ADR-0011 決策 4）。 */
    public record RestockLine(Long skuId, int quantity) {
    }

    public static RefundRequestedEvent of(ReturnRequest request, Instant now) {
        return new RefundRequestedEvent(
                UUID.randomUUID().toString(),
                request.returnNo().value(),
                request.orderNo().value(),
                request.userId(),
                request.refundAmount(),
                request.restockableLines().stream()
                        .map(line -> new RestockLine(line.skuId(), line.quantity()))
                        .toList(),
                now);
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    /**
     * 以訂單號作為 partition key，而非退貨單號。
     *
     * <p>同一張訂單的多次退款必須有序——併發處理兩張退貨單時，
     * 「累計退款 ≤ 已付」的檢查會同時讀到舊值。
     * 依訂單分區讓它們排成一列，樂觀鎖就只是最後一道保險而不是主要防線。
     */
    @Override
    public String aggregateId() {
        return orderNo;
    }
}
