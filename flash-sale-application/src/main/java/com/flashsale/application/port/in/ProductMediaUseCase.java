package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ProductImageView;
import com.flashsale.application.port.in.dto.UploadAuthorization;

import java.util.List;
import java.util.Map;

/** 商品圖片（ADR-0027）。 */
public interface ProductMediaUseCase {

    /**
     * 要一張上傳授權。
     *
     * @param sha256      檔案內容的雜湊，由<b>前端</b>算。
     * 伺服器不驗證它是否真的等於內容——
     * 驗證需要讀取整個檔案，而那正是我們在避免的事。
     * 算錯的後果是「同一張圖存了兩份」，
     * 是浪費而不是錯誤
     * @param contentType 只接受 JPEG / PNG / WebP（白名單）
     */
    UploadAuthorization authorizeUpload(Long userId, String sha256,
                                        String contentType, long byteSize);

    /** 上傳完成，把物件掛到商品上。 */
    ProductImageView attach(Long productId, String objectKey,
                           String contentType, long byteSize);

    /** 取消掛載。 */
    void detach(Long productId, Long imageId);

    /** 一個商品的圖片，依排序。 */
    List<ProductImageView> imagesOf(Long productId);

    /** 批次取多個商品的<b>主圖</b>，供列表使用。 */
    Map<Long, ProductImageView> primaryImagesOf(List<Long> productIds);
}
