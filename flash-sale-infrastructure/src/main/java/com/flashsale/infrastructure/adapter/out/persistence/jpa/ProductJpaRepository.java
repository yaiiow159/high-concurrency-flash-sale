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

    /**
     * 關鍵字模糊比對——搜尋引擎故障時的降級路徑。
     *
     * <p>{@code LIKE '%keyword%'} 前面帶萬用字元，索引用不上、必然全表掃描。
     * 這是刻意接受的：它只在 ES 掛掉時才會走到，而那時候「慢但有結果」
     * 遠好過「快但沒有」。為這條路徑加索引等於讓正常路徑一直付出寫入成本。
     */
    @Query("""
            select p from ProductEntity p
            where p.status = 'ON_SHELF'
              and (:categoryId is null or p.categoryId = :categoryId)
              and (:keyword = '' or lower(p.name) like lower(concat('%', :keyword, '%'))
                   or lower(p.brand) like lower(concat('%', :keyword, '%')))
            order by p.id desc
            """)
    List<ProductEntity> searchByKeyword(@Param("keyword") String keyword,
                                        @Param("categoryId") Long categoryId,
                                        Pageable pageable);

    @Query("select s from SkuEntity s where s.id in :ids")
    List<SkuEntity> findSkusByIds(@Param("ids") List<Long> ids);

    /** 依 SKU 反查商品；秒殺活動引用 SKU，但組訂單行快照需要商品名稱。 */
    @EntityGraph(attributePaths = "skus")
    @Query("select p from ProductEntity p join p.skus s where s.id = :skuId")
    Optional<ProductEntity> findBySkuId(@Param("skuId") Long skuId);

    /**
     * 依多個 SKU 反查商品，供購物車一次帶出所有品項的名稱與價格。
     *
     * <p><b>distinct 不可省</b>：同一個商品的多個 SKU 同時在購物車裡時
     * （例如 256G 與 512G 各買一件），join 會讓那個商品出現兩次。
     */
    @EntityGraph(attributePaths = "skus")
    @Query("select distinct p from ProductEntity p join p.skus s where s.id in :skuIds")
    List<ProductEntity> findBySkuIds(@Param("skuIds") List<Long> skuIds);

    /** 列表頁的「NT$ x 起」：一次取回多個商品的最低價，避免 N+1。 */
    @Query("""
            select s.product.id, min(s.price) from SkuEntity s
            where s.product.id in :productIds group by s.product.id
            """)
    List<Object[]> findLowestPrices(@Param("productIds") List<Long> productIds);
}
