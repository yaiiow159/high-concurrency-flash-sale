package com.flashsale.application.port.in.dto;

import java.math.BigDecimal;
import java.util.List;

/** 結帳試算。 */
public record CheckoutPreview(
        BigDecimal subtotal,
        List<OrderView.Discount> discounts,
        BigDecimal totalDiscount,
        /** <b>商品</b>折後應付，不含運費 */
        BigDecimal payable,
        /** 已扣掉免運折抵的實收運費。 */
        BigDecimal shippingFee,
        /** 有沒有足夠資訊算運費（選了地址沒有）。 */
        boolean shippingKnown,
        /** 推導出來的區域名稱；用來解釋「為什麼這一單運費比較貴」。 */
        String shippingZone,
        /** 總計 = payable + shippingFee。付款金額就是這個數字。 */
        BigDecimal total,
        List<Line> lines
) {

    /**
     * @param paidAmount 折扣分攤到這一行的實付金額。先顯示出來，
     * 使用者退貨時看到的數字才不會與結帳時看到的不一致
     */
    public record Line(Long skuId, String skuSnapshot, BigDecimal unitPrice,
                       int quantity, BigDecimal subtotal, BigDecimal paidAmount) {
    }
}
