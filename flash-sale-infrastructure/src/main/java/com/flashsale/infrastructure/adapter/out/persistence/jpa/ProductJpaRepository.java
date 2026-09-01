package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.ProductEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.SkuEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 商品的 Spring Data 介面。 */
public interface ProductJpaRepository extends JpaRepository<ProductEntity, Long> {

    /** 聚合根被載入後就該是完整的——半個聚合根比沒有更危險。 */
    @EntityGraph(attributePaths = "skus")
    Optional<ProductEntity> findWithSkusById(Long id);

    /**
     * 列表查詢。
     *
     * <p><b>刻意不用 EntityGraph</b>：列表只需要商品本身與最低價，
     * 一次帶出所有 SKU 會讓回應大上數倍。最低價由另一個查詢批次取得。
     */
    @Query("""
            select p from ProductEntity p
            where p.status = 'ON_SHELF'
              and (:categoryId is null or p.categoryId = :categoryId)
            order by p.id desc
            """)
    List<ProductEntity> findOnShelf(@Param("categoryId") Long categoryId, Pageable pageable);

    @Query("select s from SkuEntity s where s.id in :ids")
    List<SkuEntity> findSkusByIds(@Param("ids") List<Long> ids);

    /** 依 SKU 反查商品；秒殺活動引用 SKU，但組訂單行快照需要商品名稱。 */
    @EntityGraph(attributePaths = "skus")
    @Query("select p from ProductEntity p join p.skus s where s.id = :skuId")
    Optional<ProductEntity> findBySkuId(@Param("skuId") Long skuId);

    /** 列表頁的「NT$ x 起」：一次取回多個商品的最低價，避免 N+1。 */
    @Query("""
            select s.product.id, min(s.price) from SkuEntity s
            where s.product.id in :productIds group by s.product.id
            """)
    List<Object[]> findLowestPrices(@Param("productIds") List<Long> productIds);
}
