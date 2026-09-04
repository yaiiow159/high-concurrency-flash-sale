package com.flashsale.application.port.in.dto;

/**
 * 商品規格的庫存狀態。
 *
 * <h2>不無條件公開精確數量</h2>
 *
 * <p>庫存量是商業情報——競爭對手可以靠它推算銷售速度與補貨週期。
 * 但「剩 3 件」對使用者是真實的購買訊號，而且是他決定要不要現在買的依據。
 *
 * <p>折衷：<b>低於門檻時才給數字</b>，其餘只說有沒有貨。
 * 這剛好覆蓋了顯示需要的兩件事——「缺貨」與「快沒了」——
 * 而在充足的時候不洩漏任何量。
 *
 * @param available 剩餘量；<b>只在低於門檻時才有值</b>，其餘為 {@code null}
 */
public record SkuStockView(Long skuId, boolean inStock, boolean lowStock, Integer available) {

    /** 低於這個量就算「快沒了」，並把確切數字給前端。 */
    public static final int LOW_STOCK_THRESHOLD = 10;

    public static SkuStockView of(Long skuId, int available) {
        boolean inStock = available > 0;
        boolean low = inStock && available < LOW_STOCK_THRESHOLD;
        return new SkuStockView(skuId, inStock, low, low ? available : null);
    }

    /** 查不到庫存列的 SKU 當成缺貨，而不是「無限有貨」。 */
    public static SkuStockView unknown(Long skuId) {
        return new SkuStockView(skuId, false, false, null);
    }
}
