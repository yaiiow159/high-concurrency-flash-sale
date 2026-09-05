package com.flashsale.application.port.out;

import com.flashsale.domain.catalog.ProductImage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 商品圖片的持久化埠（出站，ADR-0027）。 */
public interface ProductImageRepository {

    /**
     * 掛載一張圖。
     *
     * <p>同一個商品重複掛同一張圖由唯一索引擋下——使用者連點兩次
     * 上傳按鈕是常態，而內容雜湊相同代表就是同一張圖。
     *
     * @return 掛好的那一筆；已經掛過時回既有的那一筆
     */
    ProductImage attach(Long productId, String objectKey, String contentType, long byteSize);

    void detach(Long productId, Long imageId);

    List<ProductImage> findByProductId(Long productId);

    /** 批次取主圖（sortOrder 最小的那一張），供列表一次帶整頁。 */
    Map<Long, ProductImage> findPrimaryByProductIds(List<Long> productIds);

    /** 記下一張已簽發的上傳授權，供孤兒對帳判斷寬限期。 */
    void recordUpload(String objectKey, Long userId);

    /** 目前資料庫裡被指向的所有物件鍵。孤兒對帳用。 */
    Set<String> allReferencedKeys();

    /**
     * 在這個時間點之後才簽發授權的物件鍵。
     *
     * <p>孤兒對帳要用它排除「剛上傳、還沒掛上」的物件——
     * 少了這一步，一個正在上傳中的檔案看起來就是孤兒。
     */
    Set<String> keysAuthorizedAfter(Instant since);
}
