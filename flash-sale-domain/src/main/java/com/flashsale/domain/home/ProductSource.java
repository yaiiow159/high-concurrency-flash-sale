package com.flashsale.domain.home;

/**
 * 商品版位的內容從哪裡來。
 *
 * <p>規則型（前四個）與人工選品（{@link #CURATED}）刻意都支援：
 * 「熱門」用規則才不會過期，「當季限定」用規則選不出來。
 */
public enum ProductSource {

    /** 熱銷排行，取自銷量聚合。 */
    BEST_SELLING,

    /** 最新上架。 */
    NEWEST,

    /** 評分最高。 */
    TOP_RATED,

    /** 指定類目底下的商品（含子樹）。 */
    CATEGORY,

    /** 人工指定的商品清單，順序即是設定的順序。 */
    CURATED;

    public boolean isCurated() {
        return this == CURATED;
    }

    public boolean needsCategory() {
        return this == CATEGORY;
    }
}
