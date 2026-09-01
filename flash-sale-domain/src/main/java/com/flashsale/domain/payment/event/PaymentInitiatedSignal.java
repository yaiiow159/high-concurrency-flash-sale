package com.flashsale.domain.payment.event;

/**
 * 付款已發起的行程內訊號。
 *
 * <p><b>這不是領域事件</b>，刻意不實作 {@code DomainEvent}、也不進 Outbox。
 * 它的唯一用途是讓模擬閘道知道「該送回調了」，屬於本機模擬的內部機制。
 *
 * <p>接上真實金流後，回調由外部系統送來，這個訊號連同
 * {@code SimulatedCallbackDispatcher} 一併刪除即可，不影響任何業務邏輯。
 */
public record PaymentInitiatedSignal(String paymentNo, String orderNo) {
}
