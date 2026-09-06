package com.flashsale.domain.catalog;

/** 商品上架狀態。 */
public enum ProductStatus {

    /** 草稿，僅營運可見。 */
    DRAFT,

    /** 已上架，可被搜尋與購買。 */
    ON_SHELF,

    /** 已下架。 */
    OFF_SHELF;

    public boolean isPurchasable() {
        return this == ON_SHELF;
    }
}
