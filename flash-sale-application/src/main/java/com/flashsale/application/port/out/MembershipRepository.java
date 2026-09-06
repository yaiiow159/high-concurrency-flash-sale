package com.flashsale.application.port.out;

import com.flashsale.domain.membership.MemberAccount;
import com.flashsale.domain.membership.PointReason;
import com.flashsale.domain.membership.PointTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 會員帳戶與積分流水的持久化埠（出站）。 */
public interface MembershipRepository {

    /** 取帳戶；沒有就<b>當場建一個空的</b>。 */
    MemberAccount findAccount(Long userId);

    /** 記一筆積分異動並同步餘額與累計消費。 */
    boolean record(Long userId, long delta, PointReason reason, String refNo,
                   BigDecimal spendDelta, Instant now);

    /** 兌換：扣點，且點數不足時<b>不扣</b>。 */
    boolean redeem(Long userId, long cost, String refNo, Instant now);

    /** 流水，新到舊。 */
    List<PointTransaction> findTransactions(Long userId, int offset, int limit);

    /** 某一筆來源單號的異動；退款要按比例扣回時需要查出原始入帳的點數。 */
    Optional<PointTransaction> findByReference(Long userId, PointReason reason, String refNo);

    /** 對帳：餘額與流水加總不符的帳戶。 */
    List<BalanceDrift> findBalanceDrifts();

    /** @param ledgerSum 流水的 delta 加總；{@code balance} 是帳戶上的快照 */
    record BalanceDrift(Long userId, long ledgerSum, long balance) {
    }
}
