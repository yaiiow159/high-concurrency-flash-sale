package com.flashsale.application.port.out;

import java.util.List;
import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.domain.catalog.Product;

/**
 * 商品搜尋索引埠（出站）。
 *
 * <p>應用層只認得這個介面，背後是 Elasticsearch 還是別的東西是實作細節。
 * 這層間接的價值在故障時最明顯：{@link #search} 的實作負責在搜尋引擎
 * 掛掉時降級回資料庫，而呼叫端完全不需要知道（ADR-0012 決策 4）。
 */
public interface ProductSearchIndex {

    /** 寫入或覆寫一筆商品文件。 */
    void index(Product product);

    /**
     * 從索引移除。
     *
     * <p>下架的商品要從搜尋結果消失，但<b>資料庫那筆不刪</b>——
     * 歷史訂單仍需要追溯「這是哪個商品」。
     */
    void remove(Long productId);

    /**
     * 搜尋。
     *
     * <p><b>不拋例外。</b> 搜尋引擎故障時回傳 {@code degraded=true} 的降級結果，
     * 而不是讓整個頁面壞掉——搜不準的代價遠低於搜不了。
     */
    ProductSearchResult search(SearchQuery query);

    /**
     * 整份重建。
     *
     * <p>三種情況會用到：mapping 改了（ES 不允許改既有欄位型別）、
     * 索引損毀、以及事件在某段時間內漏掉。
     * <b>沒有重建能力的讀模型是不可維護的</b>——任何一次 mapping 變更都會變成停機。
     *
     * @return 重建的文件筆數
     */
    long reindexAll();

    /**
     * 索引裡目前有哪些商品 ID。
     *
     * <p>供對帳比對用。只取 ID 不取內容——對帳問的是「在不在」，
     * 把整份文件撈回來只會讓一次對帳變成一次記憶體壓力測試。
     */
    java.util.Set<Long> allIndexedIds();

    /**
     * 查詢條件。
     *
     * @param keyword    關鍵字；空字串代表不限，用於純分類瀏覽
     * @param categoryId 類目篩選，{@code null} 為不限
     * @param brand      品牌篩選，{@code null} 為不限
     */
    /**
     * 搜尋建議：依前綴比對商品名與品牌，回傳去重後的候選字。
     *
     * <p><b>回字串而不是商品</b>：建議列表的作用是幫使用者「把字打完」，
     * 不是提前給結果。回商品的話，使用者會以為那就是全部的結果，
     * 然後不再按下搜尋——而建議只取前幾筆。
     *
     * <p>索引故障時回空清單，不拋例外。建議是錦上添花，
     * 它掛掉不該讓輸入框跟著壞掉——搜尋本身已經有降級路徑了。
     */
    List<String> suggest(String prefix, int limit);

    record SearchQuery(String keyword, Long categoryId, String brand, int page, int size) {
    }
}
