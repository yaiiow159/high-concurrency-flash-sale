package com.flashsale.domain.catalog;

/** 圖片尺寸變體（ADR-0027 決策 4）。 */
public enum ImageVariant {

    /** 後台列與商品頁的縮圖列。 */
    THUMB("thumb", 160),
    /** 商品列表的卡片。列表一次載十幾張，這是流量的大宗。 */
    LIST("list", 480),
    /** 商品頁的主視覺。 */
    DETAIL("detail", 1200);

    private final String suffix;
    private final int maxEdge;

    ImageVariant(String suffix, int maxEdge) {
        this.suffix = suffix;
        this.maxEdge = maxEdge;
    }

    public int maxEdge() {
        return maxEdge;
    }

    /** 由原圖的物件鍵推出變體的鍵。 */
    public String keyOf(String originalKey) {
        int dot = originalKey.lastIndexOf('.');
        String base = dot < 0 ? originalKey : originalKey.substring(0, dot);
        String ext = dot < 0 ? "" : originalKey.substring(dot);
        return base + "_" + suffix + ext;
    }
}
