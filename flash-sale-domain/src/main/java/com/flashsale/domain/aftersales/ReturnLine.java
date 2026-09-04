package com.flashsale.domain.aftersales;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 退貨行——訂單行的一部分。
 *
 * <p><b>{@code unitPrice} 是從訂單行複製過來的快照，不是重新查來的價格。</b>
 * 訂單行本身就已經是下單當時的快照（見 {@code OrderLine}），
 * 這裡再複製一次是為了讓退款金額在退貨單上自我完備：
 * 稽核一張退貨單時不必回頭拼訂單，就能驗證退了多少錢、憑什麼。
 *
 * @param restockable 驗收結果：{@code null} 表示還沒驗收。
 *                    {@code false} 代表收到的貨不可再售，庫存不回補——
 *                    此時<b>不補任何庫存流水</b>，因為原本的 DEDUCT
 *                    已經記過那批貨離開，報廢只是它真的沒回來
 */
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

    /**
     * 指定退款金額的退貨行。
     *
     * <p><b>退款金額不再由單價推導</b>——整單折扣是折在訂單上、退貨卻是退一行，
     * 「單價 × 數量」退的是使用者<b>沒有付過</b>的錢。
     * 金額由 {@code OrderLine.refundFor} 依當時的分攤算出後傳入。
     *
     * <p>單價仍然保留：它回答「這件商品的定價是多少」，
     * 而退款金額回答「這一次退了多少」。少了前者，退貨單上就看不出折了多少。
     */
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
