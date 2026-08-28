package com.flashsale.domain.stock;

import java.util.Objects;

/**
 * 庫存扣減的完整結果。
 *
 * <p>{@code orderNo} 在兩種情況下有值：
 * <ul>
 *   <li>{@link StockDeductionOutcome#SUCCESS}：本次新扣減所綁定的訂單號</li>
 *   <li>{@link StockDeductionOutcome#DUPLICATE_REQUEST}：<b>首次</b>扣減時綁定的訂單號</li>
 * </ul>
 *
 * <p>第二種情況是真正的冪等語意：重送相同 requestId，使用者拿回的是同一張訂單，
 * 而不是一個「重複請求」的錯誤畫面。使用者連點兩次不該被懲罰。
 */
public record StockDeductionResult(StockDeductionOutcome outcome, String orderNo) {

    public StockDeductionResult {
        Objects.requireNonNull(outcome, "outcome 不可為 null");
    }

    public static StockDeductionResult success(String orderNo) {
        return new StockDeductionResult(StockDeductionOutcome.SUCCESS, orderNo);
    }

    public static StockDeductionResult duplicate(String existingOrderNo) {
        return new StockDeductionResult(StockDeductionOutcome.DUPLICATE_REQUEST, existingOrderNo);
    }

    public static StockDeductionResult rejected(StockDeductionOutcome outcome) {
        return new StockDeductionResult(outcome, null);
    }

    public boolean isSuccess() {
        return outcome.isSuccess();
    }

    public boolean isDuplicate() {
        return outcome == StockDeductionOutcome.DUPLICATE_REQUEST;
    }

    /** 已成功佔到庫存（新扣減或重送同一請求），呼叫端都應回覆使用者「搶購成功」。 */
    public boolean holdsStock() {
        return isSuccess() || (isDuplicate() && orderNo != null);
    }
}
