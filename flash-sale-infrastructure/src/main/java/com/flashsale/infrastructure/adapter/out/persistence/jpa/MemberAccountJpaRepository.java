package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.MemberAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

/**
 * 會員帳戶的增量更新。
 *
 * <p>每一句都是「加上去」，沒有一句是「設成」——理由與
 * {@code ProductRatingJpaRepository} 相同：讀出來在 Java 裡加完再寫回去，
 * 兩個並行的入帳會吃掉其中一筆。
 */
public interface MemberAccountJpaRepository extends JpaRepository<MemberAccountEntity, Long> {

    /**
     * 入帳／扣回。
     *
     * <p><b>沒有守衛條件，因為它不需要。</b> 這條路徑同時服務入帳與退款扣回，
     * 而退款絕對不能因為「積分不夠扣」而失敗——那會變成「錢退不了」。
     * 餘額因此允許為負，那是真實的債務（ADR-0016 決策 6）。
     *
     * <p>{@code cumulativeSpend} 用 {@code GREATEST(0, ...)} 夾住下界。
     * 理論上扣回不會超過當初累積的金額，但這是退款路徑——
     * 它寧可算出一個保守的等級，也不能因為約束衝突而讓錢退不出去。
     *
     * <p>{@code tier} 一併重算並存下來，但它只是<b>快取</b>：
     * 真實來源永遠是 {@code cumulative_spend}，讀取端會當場推導。
     * 存它是為了讓「有多少白金會員」這種後台查詢不必掃全表算。
     */
    @Modifying
    @Query(value = """
            update member_account
               set point_balance = point_balance + :delta,
                   cumulative_spend = greatest(0, cumulative_spend + :spendDelta),
                   tier = case
                       when greatest(0, cumulative_spend + :spendDelta) >= 200000 then 'PLATINUM'
                       when greatest(0, cumulative_spend + :spendDelta) >= 50000 then 'GOLD'
                       when greatest(0, cumulative_spend + :spendDelta) >= 10000 then 'SILVER'
                       else 'BRONZE'
                   end
             where user_id = :userId
            """, nativeQuery = true)
    int applyDelta(@Param("userId") Long userId,
                   @Param("delta") long delta,
                   @Param("spendDelta") BigDecimal spendDelta);

    /**
     * 兌換：點數不足時不扣。
     *
     * <p><b>{@code point_balance >= :cost} 這個條件是整個併發安全的所在。</b>
     * 檢查與扣減在資料庫的同一個語句內完成，中間沒有任何交易能插進來。
     * 改成先 SELECT 再 UPDATE，兩個並行請求都會讀到「還有 500 點」然後各扣一次。
     *
     * <p>這與 {@code InventoryJpaRepository.deductAvailable} 完全同形——
     * 同一個問題（有限資源的併發消耗）就該用同一個解法。
     *
     * @return 受影響列數；{@code 0} 表示點數不足，扣減未發生
     */
    @Modifying
    @Query("""
            update MemberAccountEntity a
               set a.pointBalance = a.pointBalance - :cost
             where a.userId = :userId
               and a.pointBalance >= :cost
            """)
    int deductPoints(@Param("userId") Long userId, @Param("cost") long cost);

    /**
     * 讀當下的餘額。
     *
     * <p><b>用原生純量查詢而不是 {@code findById}。</b>
     * 剛剛的 UPDATE 是原生語句，它不經過持久化上下文；
     * 此時再用 {@code findById} 讀會拿到<b>快取裡那個更新前的實體</b>，
     * 於是流水的 {@code balance_after} 永遠差一筆。
     *
     * <p>純量查詢不進一級快取，直接打資料庫，在同一個交易裡看得到自己剛寫的值。
     * 這比 {@code clearAutomatically = true} 安全——後者會把呼叫端
     * （例如退款流程裡的 Order 與 Payment）也一起分離掉。
     */
    @Query(value = "select point_balance from member_account where user_id = :userId",
            nativeQuery = true)
    Long findBalance(@Param("userId") Long userId);
}
