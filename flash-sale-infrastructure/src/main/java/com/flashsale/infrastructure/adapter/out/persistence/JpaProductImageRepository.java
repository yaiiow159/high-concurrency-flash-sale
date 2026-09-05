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

/**
 * 商品圖片的持久化（ADR-0027）。
 *
 * <p>用原生 SQL：掛載走 upsert（重複掛同一張圖是常態，
 * 不該拋例外），而孤兒對帳要的是整欄的鍵集合，兩者都不適合具名查詢。
 */
@Repository
public class JpaProductImageRepository implements ProductImageRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 掛載。
     *
     * <p>用 upsert 而不是「先查再插」：後者是 read-modify-write，
     * 兩個並行的掛載請求都會通過檢查然後各插一筆，
     * 而唯一索引會讓其中一筆爆掉——使用者看到的是「系統異常」。
     *
     * <p>排序取現有最大值加一；第一張是 0，也就是主圖。
     */
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
                        select id, sort_order from product_image
                        where product_id = :productId and object_key = :objectKey
                        """)
                .setParameter("productId", productId)
                .setParameter("objectKey", objectKey)
                .getSingleResult();

        return new ProductImage(((Number) row[0]).longValue(), productId, objectKey,
                contentType, byteSize, ((Number) row[1]).intValue());
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
                        select id, object_key, content_type, byte_size, sort_order
                        from product_image where product_id = :productId
                        order by sort_order asc, id asc
                        """)
                .setParameter("productId", productId)
                .getResultList();
        return rows.stream().map(row -> toDomain(row, productId)).toList();
    }

    /**
     * 批次取主圖。
     *
     * <p>用視窗函式一次取回每個商品排序最前的那一張——
     * 逐商品查是 N+1，而商品列表已經因為那個問題被修過一次。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, ProductImage> findPrimaryByProductIds(List<Long> productIds) {
        if (productIds.isEmpty()) {
            // 空集合會產生 `in ()` 這種在部分資料庫上非法的 SQL
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select product_id, id, object_key, content_type, byte_size, sort_order
                        from (
                            select product_id, id, object_key, content_type, byte_size, sort_order,
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
                    ((Number) row[5]).intValue()));
        }
        return result;
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

    private static ProductImage toDomain(Object[] row, Long productId) {
        return new ProductImage(((Number) row[0]).longValue(), productId,
                (String) row[1], (String) row[2], ((Number) row[3]).longValue(),
                ((Number) row[4]).intValue());
    }
}
