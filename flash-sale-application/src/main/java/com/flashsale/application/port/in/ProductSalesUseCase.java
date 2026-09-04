package com.flashsale.application.port.in;

import java.util.Map;

/** 商品銷量的計入與扣回。 */
public interface ProductSalesUseCase {

    /**
     * 把一張已付款訂單計入銷量。
     *
     * <p>時點取<b>付款成功</b>而不是下單：下單只是意圖，收到錢才是成交。
     * 這也讓「未付款逾時關單」不必特別處理——它根本沒有被計入過。
     *
     * @return 這次真的計入了才回 {@code true}
     */
    boolean recordSale(String orderNo, Long userId);

    /**
     * 退貨扣回。
     *
     * <p>不扣的話「買了再退」就能把商品刷上暢銷榜，而那可以無限重複。
     * 與積分扣回同一個立場（CLAUDE.md 7-4）。
     *
     * <p><b>扣的是這一次退的量，不是整張訂單</b>——部分退貨很常見，
     * 整張扣會讓銷量比實際少。冪等鍵因此是退貨單號而非訂單號。
     *
     * @param quantityBySku 這一次退貨的 SKU 與件數
     */
    boolean recordReturn(String returnNo, Map<Long, Integer> quantityBySku);
}
