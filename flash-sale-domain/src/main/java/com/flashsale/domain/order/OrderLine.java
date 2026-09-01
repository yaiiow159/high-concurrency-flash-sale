package com.flashsale.domain.order;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 訂單行。
 *
 * <p><b>{@code skuSnapshot} 與 {@code unitPrice} 是快照，不是引用。</b>
 * 商家調價或改商品名之後，歷史訂單不能跟著變——那是財務問題，不是顯示問題。
 * 客訴時使用者說「我買的時候寫 990」，系統必須拿得出當時的數字。
 *
 * <p>同時保留 {@code skuId}：快照回答「當時買了什麼」，
 * ID 回答「那是哪一個商品」。兩者缺一，前者無法追溯，後者無法還原。
 *
 * @param sourceActivityId 此行來自哪一個秒殺活動；一般下單為 {@code null}。
 *                         放在行而非訂單上，是因為「這一件商品是在活動中買的」
 *                         才是準確的語意——未來購物車若容許混入一件秒殺商品，
 *                         這個模型不需要改
 */
public record OrderLine(
        Long skuId,
        String skuSnapshot,
        BigDecimal unitPrice,
        int quantity,
        Long sourceActivityId
) {

    public OrderLine {
        Objects.requireNonNull(skuId, "skuId 不可為 null");
        if (skuSnapshot == null || skuSnapshot.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "商品快照不可為空");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "單價不可為負數");
        }
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "訂單行數量必須大於 0");
        }
    }

    /** 小計。由單價與數量推導，不獨立儲存——避免出現兩個真實來源。 */
    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public boolean isFromActivity(Long activityId) {
        return Objects.equals(sourceActivityId, activityId);
    }
}
