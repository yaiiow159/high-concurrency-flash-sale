package com.flashsale.domain.catalog;

/**
 * 商品的銷量聚合。
 *
 * <h2>存總數，不存比率</h2>
 *
 * <p>與 {@code product_rating} 同一個判斷（CLAUDE.md 7-3）：
 * 存下來的必須是**原始事實**，因為從總數算得出任何衍生值，
 * 反過來卻不行。
 *
 * <h2>件數與訂單數分開存</h2>
 *
 * <p>只有件數的話，「10 個人各買 1 件」與「1 個人買 10 件」
 * 在排行榜上一模一樣——而那兩件事的熱門程度差很多。
 *
 * @param soldQuantity 累計售出件數
 * @param orderCount   累計訂單數，同一張訂單只算一次
 */
public record ProductSales(Long productId, long soldQuantity, long orderCount) {

    public static ProductSales none(Long productId) {
        return new ProductSales(productId, 0, 0);
    }
}
