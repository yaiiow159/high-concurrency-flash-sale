package com.flashsale.domain.aftersales;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/** 退貨行——訂單行的一部分。 */
public record ReturnLine(
        Long skuId,
        String skuSnapshot,
        BigDecimal unitPrice,
        int quantity,
        Boolean restockable,
        BigDecimal refundAmount
) {

    public ReturnLine {
        Objects.requireNonNull(skuId, "skuId 不可為 null");
        if (skuSnapshot == null || skuSnapshot.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "商品快照不可為空");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "單價不可為負數");
        }
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "退貨數量必須大於 0");
        }
        if (refundAmount == null || refundAmount.signum() < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "退款金額不可為負數");
        }
        if (refundAmount.compareTo(unitPrice.multiply(BigDecimal.valueOf(quantity))) > 0) {
            // 退得比定價多，一定是分攤算錯了。這裡是最後一個還能便宜擋下的地方——
            // 再往下就是真的把錢送出去
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "退款金額不可高於原始小計");
        }
    }

    /** 無折扣訂單的退貨行：退款就是單價 × 數量。 */
    public static ReturnLine of(Long skuId, String skuSnapshot, BigDecimal unitPrice, int quantity) {
        return of(skuId, skuSnapshot, unitPrice, quantity,
                unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    /** 指定退款金額的退貨行。 */
    public static ReturnLine of(Long skuId, String skuSnapshot, BigDecimal unitPrice,
                                int quantity, BigDecimal refundAmount) {
        return new ReturnLine(skuId, skuSnapshot, unitPrice, quantity, null, refundAmount);
    }

    public ReturnLine inspected(boolean canRestock) {
        return new ReturnLine(skuId, skuSnapshot, unitPrice, quantity, canRestock, refundAmount);
    }

    /** 驗收後判定可再售，庫存要回補到一般庫存池。 */
    public boolean shouldRestock() {
        return Boolean.TRUE.equals(restockable);
    }
}
