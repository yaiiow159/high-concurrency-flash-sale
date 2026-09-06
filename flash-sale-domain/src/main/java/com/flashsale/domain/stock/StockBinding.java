package com.flashsale.domain.stock;

import java.util.Objects;

/** 一筆「已扣減庫存」的憑證。 */
public record StockBinding(String requestId, String orderNo, long userId, int quantity) {

    public StockBinding {
        Objects.requireNonNull(requestId, "requestId 不可為 null");
        Objects.requireNonNull(orderNo, "orderNo 不可為 null");
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity 不可為負數");
        }
    }

    /** 資訊是否完整到足以安全地退回這筆扣減。 */
    public boolean isReversible() {
        return quantity > 0;
    }
}
