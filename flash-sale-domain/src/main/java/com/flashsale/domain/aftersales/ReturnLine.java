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
        Boolean restockable
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
    }

    /** 尚未驗收的退貨行。 */
    public static ReturnLine of(Long skuId, String skuSnapshot, BigDecimal unitPrice, int quantity) {
        return new ReturnLine(skuId, skuSnapshot, unitPrice, quantity, null);
    }

    /** 退款金額。與 {@code OrderLine.subtotal()} 同樣由單價推導，不獨立儲存。 */
    public BigDecimal refundAmount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public ReturnLine inspected(boolean canRestock) {
        return new ReturnLine(skuId, skuSnapshot, unitPrice, quantity, canRestock);
    }

    /** 驗收後判定可再售，庫存要回補到一般庫存池。 */
    public boolean shouldRestock() {
        return Boolean.TRUE.equals(restockable);
    }
}
