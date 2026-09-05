package com.flashsale.infrastructure.adapter.out.media;

import com.flashsale.application.port.out.MediaStorage;
import com.flashsale.infrastructure.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * S3 相容的物件儲存（ADR-0027）。
 *
 * <p>用 AWS SDK 而不是 MinIO 專用客戶端：介面是 S3 相容的，
 * 綁 MinIO 的客戶端等於把本機開發用的東西帶進正式環境的相依裡。
 */
@Component
public class S3MediaStorage implements MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(S3MediaStorage.class);

    private final S3Client client;
    private final S3Presigner presigner;
    private final MediaProperties properties;

    public S3MediaStorage(S3Client client, S3Presigner presigner, MediaProperties properties) {
        this.client = client;
        this.presigner = presigner;
        this.properties = properties;
    }

    /**
     * 簽一個可以 PUT 的臨時 URL。
     *
     * <p><b>把 content type 與長度綁進簽章</b>：不綁的話，
     * 拿到這個 URL 的人可以上傳任何東西、任意大小到我們的桶裡——
     * 而預簽名 URL 會出現在瀏覽器的網路面板上，不是秘密。
     */
    @Override
    public String presignUpload(String objectKey, String contentType, long byteSize,
                                Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .contentType(contentType)
                .contentLength(byteSize)
                .build();

        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(put)
                        .build())
                .url()
                .toString();
    }

    @Override
    public byte[] download(String objectKey) {
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build()).asByteArray();
        } catch (NoSuchKeyException absent) {
            return null;
        }
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        client.putObject(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength((long) content.length)
                        .build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public String publicUrl(String objectKey) {
        return properties.publicBaseUrl() + "/" + objectKey;
    }

    /**
     * 物件在不在。
     *
     * <p>上傳回報之後用它確認，<b>而不是相信前端說的</b>——
     * 位元組不經過伺服器的代價是伺服器也不知道上傳有沒有成功，
     * 而「前端說成功了」與「物件真的在」是兩件事。
     */
    @Override
    public boolean exists(String objectKey) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException absent) {
            return false;
        } catch (Exception unavailable) {
            // 儲存端不可用時回 false，讓上傳回報失敗而不是掛上一個
            // 可能不存在的物件——寧可要孤兒，不可要破圖
            log.warn("檢查物件失敗，視為不存在 key={}", objectKey, unavailable);
            return false;
        }
    }

    @Override
    public Set<String> allKeys() {
        Set<String> keys = new HashSet<>();
        String token = null;
        do {
            final String continuation = token;
            var response = client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(properties.bucket())
                    .continuationToken(continuation)
                    .build());
            response.contents().stream().map(S3Object::key).forEach(keys::add);
            token = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken()
                    : null;
        } while (token != null);
        return keys;
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .build());
        log.warn("已永久刪除物件 key={}", objectKey);
    }
}
