package com.flashsale.application.port.in.dto;

import java.math.BigDecimal;
import java.util.List;

/** 購物車的對外表述。 */
public record CartView(
        List<Item> items,
        BigDecimal totalAmount,
        int totalQuantity,
        int removedCount
) {

    /**
     * @param purchasable 目前是否可購買。已下架的品項<b>留在清單裡並標記</b>，
     * 不直接刪掉——讓使用者知道發生了什麼
     * @param subtotal    以<b>當下</b>價格計算，僅供預覽。
     * 真正的金額在下單時重新計算並凍結進訂單
     */
    public record Item(
            Long skuId,
            Long productId,
            String productName,
            String specDisplay,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal subtotal,
            boolean purchasable
    ) {
    }

    public static CartView empty() {
        return new CartView(List.of(), BigDecimal.ZERO, 0, 0);
    }
}
