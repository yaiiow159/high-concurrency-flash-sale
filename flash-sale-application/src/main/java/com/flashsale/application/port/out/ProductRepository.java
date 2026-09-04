package com.flashsale.application.port.out;

import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.Sku;

import java.util.List;
import java.util.Optional;

/** 商品持久化埠（出站）。 */
public interface ProductRepository {

    Product save(Product product);

    /** 依 ID 取商品，含其所有 SKU——聚合根被載入後就該是完整的。 */
    Optional<Product> findById(Long productId);

    /**
     * 更新商品的上架狀態。
     *
     * <p>只開放狀態這一個欄位：名稱、描述、SKU 一旦有人下過單就不該再動，
     * 那會讓訂單裡的商品快照與商品本身對不上——而快照才是財務憑據。
     */
    Product updateStatus(Product product);

    /**
     * 所有已上架商品的 ID。
     *
     * <p>供搜尋索引對帳比對用。同樣只取 ID：對帳要的是集合差異，
     * 而把所有商品連同 SKU 一起載入會在商品數上萬時直接吃掉一大塊堆積。
     */
    java.util.Set<Long> findOnShelfIds();

    /**
     * 依類目列出已上架商品。
     *
     * @param categoryId {@code null} 表示不限類目
     */
    List<Product> findOnShelf(Long categoryId, int limit, int offset);

    /**
     * 依 SKU 反查其所屬商品。
     *
     * <p>秒殺活動引用的是 SKU，但要組出訂單行的商品快照需要商品名稱。
     */
    Optional<Product> findBySkuId(Long skuId);

    /** 批次取 SKU，供結帳時一次取得多個品項的價格與狀態。 */
    List<Sku> findSkusByIds(List<Long> skuIds);

    /**
     * 依多個 SKU 反查商品，供購物車一次帶出所有品項的名稱與價格。
     *
     * <p>逐筆查在 50 個品項的購物車上就是 50 次往返，
     * 而購物車頁正是使用者反覆重整的頁面。
     */
    List<Product> findBySkuIds(List<Long> skuIds);

    /**
     * 關鍵字模糊比對——<b>搜尋引擎故障時的降級路徑</b>（ADR-0012 決策 4）。
     *
     * <p>只做 {@code LIKE}：沒有分詞、沒有相關性排序、沒有分面。
     * 那正是引入 Elasticsearch 的理由，也是為什麼降級結果要標記 degraded——
     * 使用者需要知道現在搜得不準，而不是以為商品不見了。
     *
     * <p>刻意不加索引優化這條查詢。它只在 ES 掛掉時才會走到，
     * 為一條故障路徑加索引，等於讓正常路徑一直付出寫入成本。
     */
    List<Product> searchByKeyword(String keyword, Long categoryId, String brand,
                                  int limit, int offset);
}
