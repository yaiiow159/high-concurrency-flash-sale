package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.ProductRatingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 評分聚合的增量更新。 */
public interface ProductRatingJpaRepository extends JpaRepository<ProductRatingEntity, Long> {

    @Modifying
    @Query("""
            update ProductRatingEntity r
               set r.ratingSum = r.ratingSum + :stars,
                   r.ratingCount = r.ratingCount + 1
             where r.productId = :productId
            """)
    int incrementTotals(@Param("productId") Long productId, @Param("stars") int stars);

    /** 換評分時只動總和，<b>不動筆數</b>。 */
    @Modifying
    @Query("""
            update ProductRatingEntity r
               set r.ratingSum = r.ratingSum + :delta
             where r.productId = :productId
            """)
    int adjustSum(@Param("productId") Long productId, @Param("delta") int delta);

    @Modifying
    @Query("update ProductRatingEntity r set r.count1 = r.count1 + :delta where r.productId = :productId")
    int adjustCount1(@Param("productId") Long productId, @Param("delta") int delta);

    @Modifying
    @Query("update ProductRatingEntity r set r.count2 = r.count2 + :delta where r.productId = :productId")
    int adjustCount2(@Param("productId") Long productId, @Param("delta") int delta);

    @Modifying
    @Query("update ProductRatingEntity r set r.count3 = r.count3 + :delta where r.productId = :productId")
    int adjustCount3(@Param("productId") Long productId, @Param("delta") int delta);

    @Modifying
    @Query("update ProductRatingEntity r set r.count4 = r.count4 + :delta where r.productId = :productId")
    int adjustCount4(@Param("productId") Long productId, @Param("delta") int delta);

    @Modifying
    @Query("update ProductRatingEntity r set r.count5 = r.count5 + :delta where r.productId = :productId")
    int adjustCount5(@Param("productId") Long productId, @Param("delta") int delta);

    @Query("select r from ProductRatingEntity r where r.productId in :productIds")
    List<ProductRatingEntity> findAllByProductIds(@Param("productIds") List<Long> productIds);

    /** 對帳：聚合與 {@code review} 表的真實統計不符的商品。 */
    @Query(value = "select r.product_id as productId, "
            + "coalesce(count(v.id), 0) as actualCount, "
            + "coalesce(sum(v.rating), 0) as actualSum, "
            + "r.rating_count as storedCount, "
            + "r.rating_sum as storedSum "
            + "from product_rating r "
            + "left join review v on v.product_id = r.product_id "
            + "group by r.product_id, r.rating_count, r.rating_sum "
            + "having coalesce(count(v.id), 0) <> r.rating_count "
            + "    or coalesce(sum(v.rating), 0) <> r.rating_sum",
            nativeQuery = true)
    List<RatingDriftRow> findRatingDrifts();

    /** 有評價、卻連一列聚合都沒有的商品。 */
    @Query(value = "select v.product_id as productId, "
            + "count(v.id) as actualCount, "
            + "sum(v.rating) as actualSum, "
            + "0 as storedCount, "
            + "0 as storedSum "
            + "from review v "
            + "where not exists (select 1 from product_rating r where r.product_id = v.product_id) "
            + "group by v.product_id",
            nativeQuery = true)
    List<RatingDriftRow> findMissingAggregates();

    /** 把聚合重算成 {@code review} 表的真實統計。 */
    @Modifying
    @Query(value = "update product_rating r set "
            + "r.rating_sum = (select coalesce(sum(v.rating), 0) from review v "
            + "                 where v.product_id = r.product_id), "
            + "r.rating_count = (select count(v.id) from review v "
            + "                   where v.product_id = r.product_id), "
            + "r.count_1 = (select count(v.id) from review v "
            + "              where v.product_id = r.product_id and v.rating = 1), "
            + "r.count_2 = (select count(v.id) from review v "
            + "              where v.product_id = r.product_id and v.rating = 2), "
            + "r.count_3 = (select count(v.id) from review v "
            + "              where v.product_id = r.product_id and v.rating = 3), "
            + "r.count_4 = (select count(v.id) from review v "
            + "              where v.product_id = r.product_id and v.rating = 4), "
            + "r.count_5 = (select count(v.id) from review v "
            + "              where v.product_id = r.product_id and v.rating = 5) "
            + "where r.product_id = :productId",
            nativeQuery = true)
    int recomputeFromReviews(@Param("productId") Long productId);

    /** 原生查詢的投影。取值方法名要對應 SQL 的別名。 */
    interface RatingDriftRow {
        Long getProductId();

        long getActualCount();

        long getActualSum();

        long getStoredCount();

        long getStoredSum();
    }
}
