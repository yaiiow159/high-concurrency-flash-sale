package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.ProductRatingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 評分聚合的增量更新。
 *
 * <h2>每一句都是「加上去」，沒有一句是「設成」</h2>
 *
 * <p>把它寫成「SELECT 出來、在 Java 裡加、UPDATE 回去」是 read-modify-write：
 * 兩個人同時評價同一件商品，兩邊都讀到 count=10，各自寫回 11，
 * 於是有一則評價從聚合上消失了——而評價表裡還在，
 * 表現出來就是「128 則評價，但平均分算起來只有 127 則」。
 *
 * <p>五個分佈桶各寫一句而不是用動態 SQL 拼欄位名：拼欄位名要嘛引入
 * 字串串接（SQL 注入的入口），要嘛引入一個 switch，
 * 而 switch 漏一個 case 的症狀是「四星評價不會出現在長條圖上」——
 * 那種錯誤要等到有人數長條圖才會被發現。五句醜，但它們醜得很明顯。
 */
public interface ProductRatingJpaRepository extends JpaRepository<ProductRatingEntity, Long> {

    @Modifying
    @Query("""
            update ProductRatingEntity r
               set r.ratingSum = r.ratingSum + :stars,
                   r.ratingCount = r.ratingCount + 1
             where r.productId = :productId
            """)
    int incrementTotals(@Param("productId") Long productId, @Param("stars") int stars);

    /**
     * 換評分時只動總和，<b>不動筆數</b>。
     *
     * <p>這是整個聚合最容易寫錯的一句。寫成「先移除再新增」的話，
     * 中間有一瞬間 {@code ratingCount} 少一，而那一瞬間剛好有人讀到
     * 就會看到錯的平均分。一句 UPDATE 沒有那個中間態。
     *
     * <p>{@code delta} 可以是負數（5 分改 1 分），因此
     * {@code ck_product_rating_sum} 的 {@code >= 0} 檢查有實質意義：
     * 算錯方向會在資料庫層就爆，而不是安靜地把平均分變成負的。
     */
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

    /**
     * 對帳：聚合與 {@code review} 表的真實統計不符的商品。
     *
     * <p><b>比對在資料庫端做完，只把不平的搬回來。</b>
     * 把所有評價撈進 Java 再分組加總，在評價數上百萬時是一次全表搬運。
     *
     * <p>{@code left join} 而非 {@code join}：一個「沒有任何評價卻有聚合數字」
     * 的商品，用 inner join 會從結果裡消失——而那正是最該被發現的異常。
     * 反向（有評價卻沒有聚合列）由 {@link #findMissingAggregates} 負責，
     * 因為那需要從 review 那一側出發。
     */
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

    /**
     * 有評價、卻連一列聚合都沒有的商品。
     *
     * <p>這種商品在商品頁上會顯示「尚無評價」——評價明明存在，
     * 卻因為聚合列缺席而完全看不到。它不會出現在 {@link #findRatingDrifts} 裡，
     * 因為那支是從 {@code product_rating} 出發的。
     */
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

    /**
     * 把聚合重算成 {@code review} 表的真實統計。
     *
     * <p><b>整列覆寫而不是增量。</b> 這是全篇唯一一處「設成」而非「加上去」的寫入，
     * 而它之所以安全，正是因為它不做增量：來源是 {@code review} 表的當下統計，
     * 不依賴聚合的舊值。並行的評價寫入可能讓這次重算稍微落後一則，
     * 而下一次對帳會再抓到它——這比「修一半」安全。
     */
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
