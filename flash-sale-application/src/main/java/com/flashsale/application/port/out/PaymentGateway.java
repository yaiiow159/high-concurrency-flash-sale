package com.flashsale.application.port.out;

import com.flashsale.domain.payment.Payment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 金流閘道埠（出站）。
 *
 * <p><b>介面刻意設計成非同步的形狀</b>，即使目前的模擬實作可以同步完成。
 * 真實金流一律是「發起 → 使用者在閘道頁面操作 → 閘道回調通知結果」，
 * 若現在把介面設計成同步回傳結果，之後接真實金流時整條鏈路都要重寫。
 *
 * <p>模擬實作要做的是「模擬那個非同步流程」，而不是「因為是模擬所以走捷徑」。
 */
public interface PaymentGateway {

    /**
     * 發起付款。
     *
     * @return 付款意圖，含導向使用者的付款頁網址
     */
    PaymentIntent initiate(Payment payment);

    /**
     * 驗證回調的簽章。
     *
     * <p><b>回調端點是對外開放的</b>——任何人都能往它送資料。
     * 少了簽章驗證，攻擊者只要送一個「付款成功」就能免費下單。
     * 這是整個付款流程中最不能省的一步。
     *
     * @param parameters 回調攜帶的全部參數（含簽章欄位）
     */
    boolean verifyCallbackSignature(Map<String, String> parameters);

    /**
     * 退款。
     *
     * <p>支援部分退款（ADR-0011）：多品項訂單可以只退其中一件。
     * 「累計退款不可超過已收金額」由 {@code Payment} 聚合根把關，
     * 不在這一層——閘道埠的責任是把錢送出去，不是判斷該不該送。
     *
     * <p><b>{@code idempotencyKey} 不是可選的。</b>退款的冪等最終只能由閘道保證：
     * 「請求已送達但回應遺失」這個狀態只有對方知道，我們這邊看到的是逾時，
     * 而逾時之後除了重試沒有別的選擇。呼叫端能做的是每次重試都送同一把鍵，
     * 讓對方認出這是同一筆而不是第二筆。真實金流（Stripe、綠界）都支援這件事，
     * 不用它等於自己承擔重複退款的風險。
     *
     * @param amount         本次退款金額，可小於已付金額
     * @param idempotencyKey 同一筆退款的重試必須使用同一把鍵；本專案用退貨單號
     */
    RefundOutcome refund(Payment payment, BigDecimal amount, String idempotencyKey);

    /** 發起付款的結果。 */
    record PaymentIntent(String gatewayReference, String paymentUrl) {
    }

    /** 退款結果。 */
    record RefundOutcome(boolean succeeded, String gatewayReference, String failureReason) {

        public static RefundOutcome success(String gatewayReference) {
            return new RefundOutcome(true, gatewayReference, null);
        }

        public static RefundOutcome failure(String reason) {
            return new RefundOutcome(false, null, reason);
        }
    }
}
