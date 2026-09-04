package com.flashsale.application.port.in.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 結帳試算。
 *
 * <p><b>這是唯讀的：不建訂單、不扣庫存、不核銷券。</b>
 * 存在的理由是使用者在按下「送出訂單」之前，就該看到這張券折多少。
 * 讓前端自己算是錯的——前端算出來的數字沒有任何約束力，
 * 而兩邊算出不同答案時使用者只會相信他先看到的那一個。
 *
 * @param discounts 折扣明細；沒有任何優惠適用時是空的，不是 null
 * @param payable   折後應付
 */
public record CheckoutPreview(
        BigDecimal subtotal,
        List<OrderView.Discount> discounts,
        BigDecimal totalDiscount,
        BigDecimal payable,
        List<Line> lines
) {

    /**
     * @param paidAmount 折扣分攤到這一行的實付金額。先顯示出來，
     *                   使用者退貨時看到的數字才不會與結帳時看到的不一致
     */
    public record Line(Long skuId, String skuSnapshot, BigDecimal unitPrice,
                       int quantity, BigDecimal subtotal, BigDecimal paidAmount) {
    }
}
