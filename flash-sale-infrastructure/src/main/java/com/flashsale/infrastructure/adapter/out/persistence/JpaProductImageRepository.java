package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.ProductImageRepository;
import com.flashsale.domain.catalog.ProductImage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 商品圖片的持久化（ADR-0027）。 */
@Repository
public class JpaProductImageRepository implements ProductImageRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** 掛載。 */
    @Override
    @Transactional
    public ProductImage attach(Long productId, String objectKey,
                               String contentType, long byteSize) {
        entityManager.createNativeQuery("""
                        insert into product_image
                            (product_id, object_key, content_type, byte_size, sort_order, created_at)
                        select :productId, :objectKey, :contentType, :byteSize,
                               coalesce((select max(existing.sort_order) + 1 from product_image existing
                                         where existing.product_id = :productId), 0),
                               now(3)
                        on duplicate key update content_type = values(content_type)
                        """)
                .setParameter("productId", productId)
                .setParameter("objectKey", objectKey)
                .setParameter("contentType", contentType)
                .setParameter("byteSize", byteSize)
                .executeUpdate();

        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        select id, sort_order, variants_ready from product_image
                        where product_id = :productId and object_key = :objectKey
                        """)
                .setParameter("productId", productId)
                .setParameter("objectKey", objectKey)
                .getSingleResult();

        return new ProductImage(((Number) row[0]).longValue(), productId, objectKey,
                contentType, byteSize, ((Number) row[1]).intValue(), flag(row[2]));
    }

    @Override
    @Transactional
    public void detach(Long productId, Long imageId) {
        entityManager.createNativeQuery(
                        "delete from product_image where id = :id and product_id = :productId")
                .setParameter("id", imageId)
                .setParameter("productId", productId)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductImage> findByProductId(Long productId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select id, object_key, content_type, byte_size, sort_order, variants_ready
                        from product_image where product_id = :productId
                        order by sort_order asc, id asc
                        """)
                .setParameter("productId", productId)
                .getResultList();
        return rows.stream().map(row -> toDomain(row, productId)).toList();
    }

    /** 批次取主圖。 */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, ProductImage> findPrimaryByProductIds(List<Long> productIds) {
        if (productIds.isEmpty()) {
            // 空集合會產生 `in ()` 這種在部分資料庫上非法的 SQL
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select product_id, id, object_key, content_type, byte_size, sort_order,
                               variants_ready
                        from (
                            select product_id, id, object_key, content_type, byte_size, sort_order,
                                   variants_ready,
                                   row_number() over (partition by product_id
                                                      order by sort_order asc, id asc) as rn
                            from product_image where product_id in (:productIds)
                        ) ranked where rn = 1
                        """)
                .setParameter("productIds", productIds)
                .getResultList();

        Map<Long, ProductImage> result = new HashMap<>();
        for (Object[] row : rows) {
            Long productId = ((Number) row[0]).longValue();
            result.put(productId, new ProductImage(((Number) row[1]).longValue(), productId,
                    (String) row[2], (String) row[3], ((Number) row[4]).longValue(),
                    ((Number) row[5]).intValue(), flag(row[6])));
        }
        return result;
    }

    /** 標記變體已產生。 */
    @Override
    @Transactional
    public void markVariantsReady(String objectKey) {
        entityManager.createNativeQuery(
                        "update product_image set variants_ready = 1 where object_key = :objectKey")
                .setParameter("objectKey", objectKey)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void recordUpload(String objectKey, Long userId) {
        // 同一張圖再次要求授權不是錯誤（前一次沒傳完就關掉分頁）。
        // 更新時間讓寬限期從最近一次算起
        entityManager.createNativeQuery("""
                        insert into media_upload (object_key, created_by, created_at)
                        values (:objectKey, :userId, now(3))
                        on duplicate key update created_at = now(3)
                        """)
                .setParameter("objectKey", objectKey)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> allReferencedKeys() {
        @SuppressWarnings("unchecked")
        List<String> keys = entityManager
                .createNativeQuery("select distinct object_key from product_image")
                .getResultList();
        return new HashSet<>(keys);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> keysAuthorizedAfter(Instant since) {
        @SuppressWarnings("unchecked")
        List<String> keys = entityManager.createNativeQuery(
                        "select object_key from media_upload where created_at >= :since")
                .setParameter("since", Timestamp.from(since))
                .getResultList();
        return new HashSet<>(keys);
    }

    /** 讀 {@code TINYINT(1)} 旗標。 */
    static boolean flag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof Number number && number.intValue() == 1;
    }

    private static ProductImage toDomain(Object[] row, Long productId) {
        return new ProductImage(((Number) row[0]).longValue(), productId,
                (String) row[1], (String) row[2], ((Number) row[3]).longValue(),
                ((Number) row[4]).intValue(), flag(row[5]));
    }
}
