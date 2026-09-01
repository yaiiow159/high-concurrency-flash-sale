package com.flashsale.domain.catalog;

/** 商品上架狀態。 */
public enum ProductStatus {

    /** 草稿，僅營運可見。 */
    DRAFT,

    /** 已上架，可被搜尋與購買。 */
    ON_SHELF,

    /**
     * 已下架。
     *
     * <p><b>下架不等於刪除。</b> 歷史訂單仍引用著它，
     * 而訂單裡的商品快照才是財務憑據——但追溯「這是哪個商品」仍需要這筆資料存在。
     * 商品資料只下架、不硬刪。
     */
    OFF_SHELF;

    public boolean isPurchasable() {
        return this == ON_SHELF;
    }
}
