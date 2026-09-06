package com.flashsale.domain.stock;

import java.util.Objects;

/** 庫存扣減的完整結果。 */
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
