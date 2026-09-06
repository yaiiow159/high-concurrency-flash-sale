package com.flashsale.domain.home;

/** 首頁版位的種類。 */
public enum SectionType {

    /** 輪播圖。內容來自 {@link CarouselSlide}，與商品無關。 */
    CAROUSEL,

    /** 商品橫向列。內容由 {@link ProductSource} 決定。 */
    PRODUCT_RAIL,

    /** 分類入口。 */
    CATEGORY_GRID,

    /** 限時搶購活動。 */
    FLASH_SALE
}
