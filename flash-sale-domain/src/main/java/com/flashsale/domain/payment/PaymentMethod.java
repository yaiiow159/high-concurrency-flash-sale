package com.flashsale.domain.payment;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

/** 付款方式。只記錄使用者選了什麼並轉交閘道；金額、冪等與回調處理不因它而異。 */
public enum PaymentMethod {
    CREDIT_CARD,
    LINE_PAY,
    ATM_TRANSFER;

    /** 沒指定時的預設。秒殺頁搶到後一鍵付款，不該多一步選擇。 */
    public static final PaymentMethod DEFAULT = CREDIT_CARD;

    /** 空值走預設；認不得的值是呼叫端的錯，不可安靜地當成預設——那會讓人以為選的方式生效了。 */
    public static PaymentMethod parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "不支援的付款方式：" + raw);
        }
    }
}
