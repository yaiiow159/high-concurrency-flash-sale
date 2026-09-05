package com.flashsale.application.service;

import com.flashsale.application.port.in.ProductMediaUseCase;
import com.flashsale.application.port.in.dto.ProductImageView;
import com.flashsale.application.port.in.dto.UploadAuthorization;
import com.flashsale.application.port.out.MediaStorage;
import com.flashsale.application.port.out.ProductImageRepository;
import com.flashsale.domain.catalog.ProductImage;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品圖片（ADR-0027）。
 *
 * <p>上傳是兩步：先要授權、瀏覽器直傳、再回報掛載。
 * 做成一步（收檔案）的話位元組會流過應用伺服器，
 * 而那條執行緒是秒殺熱路徑要用的。
 */
@Service
public class ProductMediaService implements ProductMediaUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProductMediaService.class);

    /** 批次查主圖的上限，與其他批次查詢一致。 */
    private static final int MAX_BATCH = 100;

    private final ProductImageRepository imageRepository;
    private final MediaStorage storage;
    private final Duration uploadTtl;

    public ProductMediaService(ProductImageRepository imageRepository, MediaStorage storage,
                               MediaUploadTtl uploadTtl) {
        this.imageRepository = imageRepository;
        this.storage = storage;
        this.uploadTtl = uploadTtl.value();
    }

    /**
     * 預簽名 URL 的有效期。
     *
     * <p>包成一個型別而不是直接注入 {@code Duration}：
     * 容器裡有好幾個 Duration 的候選，用型別區分比用
     * {@code @Qualifier} 的字串安全——字串打錯要到啟動時才會發現。
     */
    public record MediaUploadTtl(Duration value) {
    }

    @Override
    @Transactional
    public UploadAuthorization authorizeUpload(Long userId, String sha256,
                                               String contentType, long byteSize) {
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            // 雜湊會變成物件鍵的一部分，而物件鍵會出現在 URL 上。
            // 不驗格式的話，一個帶 ../ 的字串就能寫到桶裡別的地方
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "sha256 格式不正確");
        }
        ProductImage.requireSupported(contentType, byteSize);

        String objectKey = ProductImage.objectKeyOf(sha256.toLowerCase(), contentType);

        // 同一張圖上傳過就不必再傳一次——內容雜湊命名讓重複上傳變成零成本。
        // 商家換規格重傳、多個商品共用情境圖都很常見
        if (storage.exists(objectKey)) {
            return new UploadAuthorization(objectKey, null, true);
        }

        // 先記下授權，再簽 URL。反過來的話，簽出去的 URL 若立刻被使用，
        // 對帳會看到一個沒有授權紀錄的物件而把它當成孤兒
        imageRepository.recordUpload(objectKey, userId);
        String uploadUrl = storage.presignUpload(objectKey, contentType, byteSize, uploadTtl);
        return new UploadAuthorization(objectKey, uploadUrl, false);
    }

    @Override
    @Transactional
    public ProductImageView attach(Long productId, String objectKey,
                                   String contentType, long byteSize) {
        ProductImage.requireSupported(contentType, byteSize);

        // **確認物件真的在，而不是相信前端說的。**
        // 位元組不經過伺服器的代價是伺服器也不知道上傳有沒有成功，
        // 而掛上一個不存在的物件就是破圖——寧可讓這次掛載失敗
        if (!storage.exists(objectKey)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA,
                    "找不到已上傳的檔案，請重新上傳");
        }

        ProductImage image = imageRepository.attach(productId, objectKey, contentType, byteSize);
        log.info("商品 {} 掛上圖片 {}", productId, objectKey);
        return ProductImageView.of(image, storage.publicUrl(objectKey));
    }

    /**
     * 取消掛載。
     *
     * <p><b>只刪關聯，不刪物件</b>（ADR-0027 決策 5）：物件儲存不能參與
     * 這個交易，而先刪物件的失敗模式是破圖。留下孤兒交給對帳——
     * 孤兒只花錢，破圖直接砸在客人臉上。
     */
    @Override
    @Transactional
    public void detach(Long productId, Long imageId) {
        imageRepository.detach(productId, imageId);
        log.info("商品 {} 取消掛載圖片 imageId={}（物件保留，交由對帳處理）", productId, imageId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductImageView> imagesOf(Long productId) {
        return imageRepository.findByProductId(productId).stream()
                .map(image -> ProductImageView.of(image, storage.publicUrl(image.objectKey())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ProductImageView> primaryImagesOf(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<Long> capped = productIds.stream().distinct().limit(MAX_BATCH).toList();
        return imageRepository.findPrimaryByProductIds(capped).values().stream()
                .collect(Collectors.toMap(ProductImage::productId,
                        image -> ProductImageView.of(image, storage.publicUrl(image.objectKey())),
                        (first, second) -> first));
    }
}
