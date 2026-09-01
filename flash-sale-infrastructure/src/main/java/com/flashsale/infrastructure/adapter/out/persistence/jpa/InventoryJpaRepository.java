package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.InventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface InventoryJpaRepository extends JpaRepository<InventoryEntity, Long> {

    /**
     * 一般下單的高頻扣減：單一條件式 UPDATE，不做「讀出來、改、寫回去」。
     *
     * <p><b>條件寫在 SQL 裡而非 Java 裡</b>，是這個方法能安全的唯一理由：
     * {@code available >= :quantity} 與扣減在資料庫的同一個語句內完成，
     * 中間沒有任何其他交易能插進來。若改成先 SELECT 再 UPDATE，
     * 兩個並行請求都會讀到「還有 1 件」然後各自扣掉，那就是超賣。
     *
     * <p>{@code version} 一併遞增，讓正在讀寫同一列的其他交易（劃撥、後台調整）
     * 的樂觀鎖能察覺。
     *
     * @return 受影響列數；{@code 0} 表示庫存不足，扣減未發生
     */
    @Modifying
    @Query("""
            update InventoryEntity i
               set i.available = i.available - :quantity,
                   i.version = i.version + 1,
                   i.updatedAt = :now
             where i.skuId = :skuId
               and i.available >= :quantity
            """)
    int deductAvailable(@Param("skuId") Long skuId,
                        @Param("quantity") int quantity,
                        @Param("now") Instant now);

    /** 退回可售量。無條件成立，因此不需要檢查回傳列數以外的東西。 */
    @Modifying
    @Query("""
            update InventoryEntity i
               set i.available = i.available + :quantity,
                   i.version = i.version + 1,
                   i.updatedAt = :now
             where i.skuId = :skuId
            """)
    int restoreAvailable(@Param("skuId") Long skuId,
                         @Param("quantity") int quantity,
                         @Param("now") Instant now);

    @Query("select i.skuId from InventoryEntity i order by i.skuId")
    List<Long> findAllSkuIds(org.springframework.data.domain.Pageable pageable);
}
