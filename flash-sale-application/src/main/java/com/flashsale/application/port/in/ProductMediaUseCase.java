package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ProductImageView;
import com.flashsale.application.port.in.dto.UploadAuthorization;

import java.util.List;
import java.util.Map;

/**
 * 商品圖片（ADR-0027）。
 *
 * <p>上傳是<b>兩步</b>：先要授權、瀏覽器直傳、再回報掛載。
 * 做成一步（收檔案）的話位元組會流過應用伺服器，而那條執行緒
 * 是秒殺熱路徑要用的。
 */
public interface ProductMediaUseCase {

    /**
     * 要一張上傳授權。
     *
     * @param sha256      檔案內容的雜湊，由<b>前端</b>算。
     *                    伺服器不驗證它是否真的等於內容——
     *                    驗證需要讀取整個檔案，而那正是我們在避免的事。
     *                    算錯的後果是「同一張圖存了兩份」，
     *                    是浪費而不是錯誤
     * @param contentType 只接受 JPEG / PNG / WebP（白名單）
     */
    UploadAuthorization authorizeUpload(Long userId, String sha256,
                                        String contentType, long byteSize);

    /**
     * 上傳完成，把物件掛到商品上。
     *
     * <p><b>會先確認物件真的在儲存裡</b>，而不是相信前端說的——
     * 位元組不經過伺服器的代價是伺服器也不知道上傳有沒有成功。
     */
    ProductImageView attach(Long productId, String objectKey,
                           String contentType, long byteSize);

    /**
     * 取消掛載。
     *
     * <p><b>不刪物件</b>（ADR-0027 決策 5）：物件儲存不能參與資料庫交易，
     * 而先刪物件的失敗模式是破圖。留下孤兒交給對帳。
     */
    void detach(Long productId, Long imageId);

    /** 一個商品的圖片，依排序。 */
    List<ProductImageView> imagesOf(Long productId);

    /**
     * 批次取多個商品的<b>主圖</b>，供列表使用。
     *
     * <p>一次帶整頁，不是每張卡各打一次——那是 N+1 在前端的樣子，
     * 而這個專案已經在商品列表上踩過一次了。
     */
    Map<Long, ProductImageView> primaryImagesOf(List<Long> productIds);
}
