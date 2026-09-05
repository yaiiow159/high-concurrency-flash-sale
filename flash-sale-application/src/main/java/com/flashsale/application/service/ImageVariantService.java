package com.flashsale.application.service;

import com.flashsale.application.port.in.ImageVariantUseCase;
import com.flashsale.application.port.out.ImageVariantGenerator;
import com.flashsale.application.port.out.MediaStorage;
import com.flashsale.application.port.out.ProductImageRepository;
import com.flashsale.domain.catalog.ImageVariant;
import com.flashsale.domain.catalog.event.ProductImageAttachedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 產生圖片的尺寸變體（ADR-0027 決策 4）。
 *
 * <p>在<b>慢車道</b>跑：原圖掛上就能用，變體晚幾秒到——
 * 那幾秒商品多半還沒上架，沒有人看得到。
 */
@Service
public class ImageVariantService implements ImageVariantUseCase {

    private static final Logger log = LoggerFactory.getLogger(ImageVariantService.class);

    private final MediaStorage storage;
    private final ImageVariantGenerator generator;
    private final ProductImageRepository imageRepository;

    public ImageVariantService(MediaStorage storage, ImageVariantGenerator generator,
                               ProductImageRepository imageRepository) {
        this.storage = storage;
        this.generator = generator;
        this.imageRepository = imageRepository;
    }

    @Override
    public void generateVariants(ProductImageAttachedEvent event) {
        String objectKey = event.objectKey();

        byte[] original = storage.download(objectKey);
        if (original == null) {
            // 物件不見了（被誤刪、或上傳其實沒成功）。往外丟會讓這則訊息
            // 一直重試，而重試不會讓物件回來——記下來讓圖片對帳去發現它。
            // 這是 fail-open：漏做縮圖的代價遠低於卡住整個分區
            log.warn("找不到原圖，略過變體 key={}", objectKey);
            return;
        }

        int produced = 0;
        for (ImageVariant variant : ImageVariant.values()) {
            String variantKey = variant.keyOf(objectKey);
            // 已經有了就跳過。消費端重放整個 topic 時這一步省掉全部的重算——
            // 而重算本身是安全的（同樣的位元組寫到同樣的鍵），只是浪費
            if (storage.exists(variantKey)) {
                produced++;
                continue;
            }
            Optional<byte[]> scaled = generator.generate(original, event.contentType(), variant);
            if (scaled.isPresent()) {
                storage.put(variantKey, scaled.get(), event.contentType());
                produced++;
            }
        }

        // **全部都成功才標記。** 只產出一部分就標記的話，
        // 前端會去要一個不存在的尺寸，而那是破圖。
        // 產不出來的情況（WebP、原圖比目標還小）就一直是 false，
        // 前端沿用原圖——那是正確的行為，不是待修的狀態
        if (produced == ImageVariant.values().length) {
            imageRepository.markVariantsReady(objectKey);
            log.debug("已產生 {} 的全部尺寸變體", objectKey);
        } else {
            log.info("{} 只產生 {}/{} 個變體，沿用原圖",
                    objectKey, produced, ImageVariant.values().length);
        }
    }
}
