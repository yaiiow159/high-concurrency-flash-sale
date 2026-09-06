package com.flashsale.application.port.out;

import com.flashsale.domain.catalog.ProductImage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 商品圖片的持久化埠（出站，ADR-0027）。 */
public interface ProductImageRepository {

    /** 掛載一張圖。 */
    ProductImage attach(Long productId, String objectKey, String contentType, long byteSize);

    void detach(Long productId, Long imageId);

    List<ProductImage> findByProductId(Long productId);

    /** 批次取主圖（sortOrder 最小的那一張），供列表一次帶整頁。 */
    Map<Long, ProductImage> findPrimaryByProductIds(List<Long> productIds);

    /** 標記某個物件的變體已產生。 */
    void markVariantsReady(String objectKey);

    /** 記下一張已簽發的上傳授權，供孤兒對帳判斷寬限期。 */
    void recordUpload(String objectKey, Long userId);

    /** 目前資料庫裡被指向的所有物件鍵。孤兒對帳用。 */
    Set<String> allReferencedKeys();

    /** 在這個時間點之後才簽發授權的物件鍵。 */
    Set<String> keysAuthorizedAfter(Instant since);
}
