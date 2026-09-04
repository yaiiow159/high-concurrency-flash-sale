package com.flashsale.domain.promotion;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 要計價的一個品項。
 *
 * <h2>為什麼不直接收 OrderLine</h2>
 *
 * <p>兩個理由，第二個才是真正的那個：
 *
 * <ol>
 *   <li>收 {@code OrderLine} 會讓 Promotion 依賴 Ordering，
 *       而優惠計算本身與「訂單」這個概念無關</li>
 *   <li><b>結帳時還沒有訂單。</b> 使用者在購物車頁看到的「套用這張券會折多少」
 *       是在下單<b>之前</b>算的——那時只有購物車品項。
 *       引擎收 OrderLine 的話，那個預覽就得先偽造一張訂單出來</li>
 * </ol>
 *
 * <p>第二點是設計上的訊號：一個只能在訂單成立後才能用的計價引擎，
 * 用起來一定會很彆扭。
 *
 * @param sourceActivityId 這個價格來自哪個秒殺活動；一般商品為 {@code null}。
 *                         秒殺不與任何優惠疊加，而判準是「價格從哪來」
 *                         而不是「這張訂單是誰建的」（ADR-0013 決策 4）
 */
public record PricedItem(
        Long skuId,
        BigDecimal unitPrice,
        int quantity,
        Long sourceActivityId
) {

    public PricedItem {
        Objects.requireNonNull(skuId, "skuId 不可為 null");
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "單價不可為負數");
        }
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "數量必須大於 0");
        }
    }

    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public boolean isFromSeckill() {
        return sourceActivityId != null;
    }
}
