package com.flashsale.domain.catalog;

/**
 * 圖片尺寸變體（ADR-0027 決策 4）。
 *
 * <h2>固定幾組，不做即時縮放</h2>
 *
 * <p>即時縮放（{@code ?w=400}）看起來更彈性，實際上是把每一次圖片請求
 * 變成一次 CPU 工作，而且快取鍵會隨參數組合爆炸——一張圖在 CDN 上
 * 可能有幾十份，而其中大部分只被看過一次。
 *
 * <h2>變體鍵仍然是內容定址的</h2>
 *
 * <p>{@code {sha256}_{suffix}.{ext}}——原圖的雜湊加後綴。
 * 同一張原圖永遠產生同一組變體鍵，所以：
 *
 * <ul>
 *   <li>重複產生是<b>冪等</b>的（同樣的位元組寫到同樣的鍵），
 *       消費端重放整個 topic 不會出問題</li>
 *   <li>URL 一樣不可變，CDN 一樣可以永久快取</li>
 * </ul>
 *
 * @param maxEdge 長邊上限（像素）。<b>等比縮放，不裁切</b>——
 *                裁切要決定裁哪裡，而那是一個沒有通用正確答案的問題
 *                （商品主體不一定在正中間）
 */
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

    /**
     * 由原圖的物件鍵推出變體的鍵。
     *
     * <p>推導而不是另外存一份對照：多存一份就多一個會不同步的地方，
     * 而它們之間的關係是純函式。
     */
    public String keyOf(String originalKey) {
        int dot = originalKey.lastIndexOf('.');
        String base = dot < 0 ? originalKey : originalKey.substring(0, dot);
        String ext = dot < 0 ? "" : originalKey.substring(dot);
        return base + "_" + suffix + ext;
    }
}
