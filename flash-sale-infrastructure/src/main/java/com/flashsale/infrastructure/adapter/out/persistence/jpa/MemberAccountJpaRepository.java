package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.MemberAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/** 會員帳戶的增量更新。 */
public interface MemberAccountJpaRepository extends JpaRepository<MemberAccountEntity, Long> {

    /** 入帳／扣回。 */
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

    /** 兌換：點數不足時不扣。 */
    @Modifying
    @Query("""
            update MemberAccountEntity a
               set a.pointBalance = a.pointBalance - :cost
             where a.userId = :userId
               and a.pointBalance >= :cost
            """)
    int deductPoints(@Param("userId") Long userId, @Param("cost") long cost);

    /** 讀當下的餘額。 */
    @Query(value = "select point_balance from member_account where user_id = :userId",
            nativeQuery = true)
    Long findBalance(@Param("userId") Long userId);

    /** 對帳：餘額與流水加總不符的帳戶。 */
    @Query(value = "select a.user_id as userId, "
            + "coalesce(sum(t.delta), 0) as ledgerSum, "
            + "a.point_balance as balance "
            + "from member_account a "
            + "left join point_transaction t on t.user_id = a.user_id "
            + "group by a.user_id, a.point_balance "
            + "having coalesce(sum(t.delta), 0) <> a.point_balance",
            nativeQuery = true)
    List<BalanceDriftRow> findBalanceDrifts();

    /** 原生查詢的投影。介面的取值方法名要對應 SQL 的別名。 */
    interface BalanceDriftRow {
        Long getUserId();

        long getLedgerSum();

        long getBalance();
    }
}
