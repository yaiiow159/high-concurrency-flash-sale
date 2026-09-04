package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.domain.catalog.ProductSummary;
import com.flashsale.infrastructure.adapter.out.persistence.entity.ProductEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.SkuEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * 列表用的商品摘要，<b>不碰 SKU 關聯</b>。
     *
     * <p>投影成建構子而不是撈 entity：撈 entity 的話 {@code toDomain}
     * 一旦讀了 {@code getSkus()} 就又變回 N+1，而那是一行不起眼的程式碼。
     * 投影讓 SKU 根本不在手上，想犯這個錯都沒有辦法。
     *
     * <p><b>keyset 分頁</b>（ADR-0021）：排序鍵就是主鍵，
     * {@code id < :cursor} 直接把掃描起點推到該去的地方，
     * 不會像 {@code OFFSET} 那樣先掃過並排序前面所有列。
     * 實測 offset 40,000 時 MySQL 會翻盤成 filesort 掃 24,564 列。
     */
    @Query("""
            select new com.flashsale.domain.catalog.ProductSummary(
                p.id, p.categoryId, p.name, p.brand,
                com.flashsale.domain.catalog.ProductStatus.ON_SHELF, null)
            from ProductEntity p
            where p.status = 'ON_SHELF'
              and (:cursor is null or p.id < :cursor)
            order by p.id desc
            """)
    List<ProductSummary> findOnShelfPage(@Param("cursor") Long cursor, Pageable pageable);

    /**
     * 同上，但限定在一組類目內。
     *
     * <p><b>與上面那個分成兩個方法，而不是用 {@code :categoryIds is null or ...}</b>：
     * JPQL 對「集合參數是否為 null」的處理在不同 Hibernate 版本之間並不一致，
     * 而失敗的樣子是查詢語法錯誤或條件被安靜忽略——後者會讓篩選失效卻沒有人發現。
     * 兩個方法各自只有一種意思。
     *
     * <p>類目集合由呼叫端從類目樹展開（ADR-0022）；
     * 涵蓋整棵樹時呼叫端會改走上面那個不帶條件的版本。
     */
    @Query("""
            select new com.flashsale.domain.catalog.ProductSummary(
                p.id, p.categoryId, p.name, p.brand,
                com.flashsale.domain.catalog.ProductStatus.ON_SHELF, null)
            from ProductEntity p
            where p.status = 'ON_SHELF'
              and p.categoryId in :categoryIds
              and (:cursor is null or p.id < :cursor)
            order by p.id desc
            """)
    List<ProductSummary> findOnShelfPageInCategories(@Param("categoryIds") Collection<Long> categoryIds,
                                                     @Param("cursor") Long cursor,
                                                     Pageable pageable);

    /**
     * 後台用：所有狀態的商品。
     *
     * <p>{@code :status is null} 才不限狀態——空字串不是「不限」，
     * 那會是一個永遠比對不到的狀態值，而症狀是「後台一片空白」。
     */
    @Query("""
            select p from ProductEntity p
            where (:status is null or p.status = :status)
            order by p.id desc
            """)
    List<ProductEntity> findAllByStatus(@Param("status") String status, Pageable pageable);

    /** 所有已上架商品的 ID。只取 ID——對帳問的是「在不在」，不需要內容。 */
    @Query("select p.id from ProductEntity p where p.status = 'ON_SHELF'")
    List<Long> findOnShelfIds();

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
              and (:brand is null or p.brand = :brand)
              and (:keyword = '' or lower(p.name) like lower(concat('%', :keyword, '%'))
                   or lower(p.brand) like lower(concat('%', :keyword, '%')))
            order by p.id desc
            """)
    List<ProductEntity> searchByKeyword(@Param("keyword") String keyword,
                                        @Param("categoryId") Long categoryId,
                                        @Param("brand") String brand,
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

    /**
     * 列表頁的「NT$ x 起」：一次取回多個商品的最低價，避免 N+1。
     *
     * <p><b>只算已上架的 SKU。</b> 下架的規格買不到，
     * 把它算進最低價會讓列表標一個點進去就不存在的價格——
     * 而使用者是照價格排序找過來的。
     */
    @Query("""
            select s.product.id, min(s.price) from SkuEntity s
            where s.product.id in :productIds and s.status = 'ON_SHELF'
            group by s.product.id
            """)
    List<Object[]> findLowestPrices(@Param("productIds") List<Long> productIds);
}
