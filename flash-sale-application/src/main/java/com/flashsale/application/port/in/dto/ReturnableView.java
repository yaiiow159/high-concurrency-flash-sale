package com.flashsale.application.port.in.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 這張訂單現在能退什麼。
 *
 * <p><b>可退數量由後端算，不讓前端自己扣。</b>
 * 「還能退幾件」的規則是「訂單行數量 − 仍佔用額度的退貨單數量」，
 * 而「仍佔用額度」包含審核中的單——那是領域規則
 * （{@code ReturnStatus.holdsReturnQuota}）。
 * 讓前端用 TypeScript 再寫一次，兩邊遲早會分岔，
 * 而分岔的症狀是「畫面說可以退，送出卻被拒絕」。
 *
 * @param returnable          整張訂單目前是否可申請退貨
 * @param reason              不可退時的原因，供畫面直接顯示；可退時為 {@code null}
 * @param requiresGoodsReturn 申請後是否需要買家寄回。未出貨的訂單不需要，
 *                            這件事必須在申請前就讓使用者知道
 */
public record ReturnableView(
        String orderNo,
        boolean returnable,
        String reason,
        boolean requiresGoodsReturn,
        List<Line> lines
) {

    /**
     * @param returnableQuantity 尚可退的數量。為 0 代表這一項已經全部申請過了，
     *                           畫面應該把它標成不可選而不是讓使用者按下去才失敗
     */
    public record Line(
            Long skuId,
            String skuSnapshot,
            BigDecimal unitPrice,
            int orderedQuantity,
            int returnableQuantity
    ) {
    }
}
