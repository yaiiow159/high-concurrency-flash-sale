package com.flashsale.application.port.in.dto;

import java.math.BigDecimal;
import java.util.List;

/** 這張訂單現在能退什麼。 */
public record ReturnableView(
        String orderNo,
        boolean returnable,
        String reason,
        boolean requiresGoodsReturn,
        List<Line> lines
) {

    /**
     * @param returnableQuantity 尚可退的數量。為 0 代表這一項已經全部申請過了，
     * 畫面應該把它標成不可選而不是讓使用者按下去才失敗
     * @param paidAmount         整單折扣分攤後，這一行<b>實際付了多少</b>。
     * 畫面上的預估退款必須用它算——有折扣的訂單，
     * {@code unitPrice} 是使用者沒有付過的錢，
     * 照定價估會讓預估金額高於實際會收到的退款
     */
    public record Line(
            Long skuId,
            String skuSnapshot,
            BigDecimal unitPrice,
            int orderedQuantity,
            int returnableQuantity,
            BigDecimal paidAmount
    ) {
    }
}
