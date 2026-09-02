package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.CartItemEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CartItemJpaRepository extends JpaRepository<CartItemEntity, Long> {

    /** 依加入時間排序，讓使用者看到的順序穩定——每次重整都換順序會讓人以為東西不見了。 */
    List<CartItemEntity> findByUserIdOrderByIdAsc(Long userId);

    void deleteByUserId(Long userId);

    @Query("select max(c.updatedAt) from CartItemEntity c where c.userId = :userId")
    Instant findLastUpdatedAt(@Param("userId") Long userId);

    /** 清理長期未動的購物車。分批刪除，避免一次刪掉數十萬列而鎖住整張表。 */
    @Query("select c.id from CartItemEntity c where c.updatedAt < :threshold")
    List<Long> findStaleIds(@Param("threshold") Instant threshold, Pageable pageable);

    @Modifying
    @Query("delete from CartItemEntity c where c.id in :ids")
    int deleteByIds(@Param("ids") List<Long> ids);
}
