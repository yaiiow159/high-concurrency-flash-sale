package com.flashsale.domain.stock;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Arrays;

/**
 * Redis Lua 庫存扣減腳本的回傳語意。
 *
 * <p>Lua 只能回傳整數，這裡把「魔術數字」翻譯成領域語彙——
 * 腳本與這個列舉是一組契約，任一方修改都必須同步，
 * {@code SeckillStockScriptTest} 會驗證兩者一致。
 */
public enum StockDeductionOutcome {

    /** 扣減成功。 */
    SUCCESS(1),

    /** 庫存不足（含剛好被別人搶完）。 */
    SOLD_OUT(0),

    /** 累計購買量將超過限購額度。 */
    USER_LIMIT_EXCEEDED(-1),

    /** Redis 中尚無此活動的庫存鍵：活動未預熱或鍵已過期。 */
    STOCK_NOT_INITIALIZED(-2),

    /** 相同 requestId 已扣減過，屬重放請求。 */
    DUPLICATE_REQUEST(-3);

    private final long code;

    StockDeductionOutcome(long code) {
        this.code = code;
    }

    public long code() {
        return code;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public static StockDeductionOutcome fromCode(long code) {
        return Arrays.stream(values())
                .filter(outcome -> outcome.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Lua 腳本回傳了未定義的碼: " + code + "，腳本與 StockDeductionOutcome 已不同步"));
    }

    /**
     * 把非成功的結果轉為對應的業務例外。
     *
     * <p>集中映射可確保「腳本新增回傳碼卻忘了處理」在此處立刻暴露，而不是回傳一個模糊的系統錯誤。
     */
    public BusinessException toException() {
        return switch (this) {
            case SUCCESS -> throw new IllegalStateException("成功結果不應轉為例外");
            case SOLD_OUT -> new BusinessException(ErrorCode.SOLD_OUT);
            case USER_LIMIT_EXCEEDED -> new BusinessException(ErrorCode.USER_PURCHASE_LIMIT_EXCEEDED);
            case STOCK_NOT_INITIALIZED -> new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND,
                    "活動庫存尚未預熱，請稍後再試");
            case DUPLICATE_REQUEST -> new BusinessException(ErrorCode.DUPLICATE_REQUEST);
        };
    }
}
