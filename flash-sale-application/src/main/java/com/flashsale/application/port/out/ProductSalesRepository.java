package com.flashsale.application.port.out;

import com.flashsale.domain.catalog.ProductSales;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 商品銷量聚合的持久化埠（出站）。 */
public interface ProductSalesRepository {

    /**
     * 計入一張訂單的銷量。
     *
     * <p><b>必須冪等，而且冪等的理由不只是「重複投遞」。</b>
     * {@code auto-offset-reset} 是 earliest，新的 consumer group 第一次上線
     * 會重放整個 topic（CLAUDE.md 鐵則 4）。銷量走增量 UPDATE，
     * 重放一次就等於把每一筆歷史訂單再加一次——
     * 症狀是「銷量憑空翻倍」，而且沒有任何錯誤訊息。
     *
     * @param quantityByProduct 商品 ID → 件數
     * @return 這次真的計入了才回 {@code true}；已經計入過回 {@code false}
     */
    boolean applySale(String orderNo, Map<Long, Integer> quantityByProduct);

    /**
     * 退貨扣回。
     *
     * <p>與積分扣回同一個立場（CLAUDE.md 7-4）：不扣的話「買了再退」
     * 就能把商品刷上暢銷榜，而那是可以無限重複的。
     *
     * <p>冪等鍵是<b>退貨單號</b>而不是訂單號：一張訂單可以退很多次，
     * 用訂單號當鍵的話第二次退貨會被當成重複而安靜略過，
     * 銷量從此永遠偏高。
     *
     * @param returnNo          退貨單號，冪等鍵
     * @param quantityByProduct <b>這一次退的</b>商品與件數，不是整張訂單的
     */
    boolean applyReturn(String returnNo, Map<Long, Integer> quantityByProduct);

    /** 批次取回，供列表頁一次帶出整頁的銷量。 */
    List<ProductSales> findByProductIds(Collection<Long> productIds);
}
