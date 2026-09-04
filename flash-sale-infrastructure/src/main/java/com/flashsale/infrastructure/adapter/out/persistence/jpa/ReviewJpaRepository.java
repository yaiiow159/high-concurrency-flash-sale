package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.ReviewEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewJpaRepository extends JpaRepository<ReviewEntity, Long> {

    Optional<ReviewEntity> findByOrderNoAndSkuId(String orderNo, Long skuId);

    @Query("""
            select r from ReviewEntity r
             where r.productId = :productId
             order by r.createdAt desc, r.id desc
            """)
    List<ReviewEntity> findByProduct(@Param("productId") Long productId, Pageable pageable);

    @Query("""
            select r from ReviewEntity r
             where r.userId = :userId
             order by r.createdAt desc, r.id desc
            """)
    List<ReviewEntity> findByUser(@Param("userId") Long userId, Pageable pageable);

    @Query("select r.skuId from ReviewEntity r where r.orderNo = :orderNo")
    List<Long> findReviewedSkuIds(@Param("orderNo") String orderNo);
}
