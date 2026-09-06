package com.flashsale.application.port.in.dto;

/** 商品規格的庫存狀態。 */
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
