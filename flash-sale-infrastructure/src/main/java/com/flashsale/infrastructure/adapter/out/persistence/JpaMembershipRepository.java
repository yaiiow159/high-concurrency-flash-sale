package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.MembershipRepository;
import com.flashsale.domain.membership.MemberAccount;
import com.flashsale.domain.membership.PointReason;
import com.flashsale.domain.membership.PointTransaction;
import com.flashsale.infrastructure.adapter.out.persistence.entity.MemberAccountEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.PointTransactionEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.MemberAccountJpaRepository;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.PointTransactionJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 會員帳戶與積分流水的 JPA 實作。 */
@Repository
public class JpaMembershipRepository implements MembershipRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaMembershipRepository.class);

    private final MemberAccountJpaRepository accountJpaRepository;
    private final PointTransactionJpaRepository transactionJpaRepository;

    public JpaMembershipRepository(MemberAccountJpaRepository accountJpaRepository,
                                   PointTransactionJpaRepository transactionJpaRepository) {
        this.accountJpaRepository = accountJpaRepository;
        this.transactionJpaRepository = transactionJpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public MemberAccount findAccount(Long userId) {
        return accountJpaRepository.findById(userId)
                .map(JpaMembershipRepository::toDomain)
                .orElseGet(() -> MemberAccount.fresh(userId));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean record(Long userId, long delta, PointReason reason, String refNo,
                          BigDecimal spendDelta, Instant now) {
        // 先查再寫，而且**在動餘額之前**。
        //
        // 重放是常態（訂單完成事件是至少一次投遞），而重放時我們什麼都不該做。
        // 若先動餘額再讓唯一索引擋下流水，餘額已經加過了——那時只能整個交易回滾，
        // 而回滾會讓消費端一直重試同一則事件。先查就沒有這個問題。
        //
        // 這道查詢**不是**併發防線（兩個並行的重放會同時通過它），
        // 真正的防線是下面那個唯一索引。兩者分工：查詢處理常見的重放，
        // 索引處理罕見的併發。
        if (transactionJpaRepository
                .findByUserIdAndReasonAndRefNo(userId, reason.name(), refNo).isPresent()) {
            log.debug("積分異動已存在，略過 userId={}, reason={}, refNo={}", userId, reason, refNo);
            return false;
        }

        ensureAccount(userId);
        BigDecimal spend = spendDelta == null ? BigDecimal.ZERO : spendDelta;
        // 先動餘額再寫流水：流水要記 balance_after，而那個值只有更新完才知道
        accountJpaRepository.applyDelta(userId, delta, spend);

        // 用原生純量查詢讀回餘額。findById 會拿到一級快取裡「更新前」的實體，
        // 而那會讓 balance_after 永遠差一筆
        long balanceAfter = balanceOf(userId, delta);

        // 唯一索引是併發下的最後一道。撞到就讓例外往上冒——
        // 餘額那句 UPDATE 已經執行，整個交易必須一起回滾。
        // 消費端重試時會走到上面那道查詢並安靜地回 false
        transactionJpaRepository.saveAndFlush(new PointTransactionEntity(
                userId, delta, balanceAfter, reason.name(), refNo, now));
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean redeem(Long userId, long cost, String refNo, Instant now) {
        ensureAccount(userId);
        if (accountJpaRepository.deductPoints(userId, cost) == 0) {
            return false;
        }
        transactionJpaRepository.saveAndFlush(new PointTransactionEntity(
                userId, -cost, balanceOf(userId, -cost),
                PointReason.COUPON_EXCHANGE.name(), refNo, now));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PointTransaction> findTransactions(Long userId, int limit, int offset) {
        return transactionJpaRepository
                .findByUser(userId, Pageables.of(limit, offset)).stream()
                .map(JpaMembershipRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PointTransaction> findByReference(Long userId, PointReason reason,
                                                      String refNo) {
        return transactionJpaRepository
                .findByUserIdAndReasonAndRefNo(userId, reason.name(), refNo)
                .map(JpaMembershipRepository::toDomain);
    }

    /** 確保帳戶存在。 */
    @Override
    @Transactional(readOnly = true)
    public List<BalanceDrift> findBalanceDrifts() {
        return accountJpaRepository.findBalanceDrifts().stream()
                .map(row -> new BalanceDrift(row.getUserId(), row.getLedgerSum(), row.getBalance()))
                .toList();
    }

    /** 更新後的餘額。查不到時退回「只有這一筆」的假設，而那只會發生在資料被外力刪除時。 */
    private long balanceOf(Long userId, long fallbackDelta) {
        Long balance = accountJpaRepository.findBalance(userId);
        return balance == null ? fallbackDelta : balance;
    }

    private void ensureAccount(Long userId) {
        if (accountJpaRepository.existsById(userId)) {
            return;
        }
        try {
            accountJpaRepository.saveAndFlush(new MemberAccountEntity(userId));
        } catch (DataIntegrityViolationException concurrent) {
            // 另一個請求搶先建好了。這正是我們要的結果
            log.debug("會員帳戶已由並行請求建立 userId={}", userId);
        }
    }

    private static MemberAccount toDomain(MemberAccountEntity entity) {
        return new MemberAccount(entity.getUserId(), entity.getPointBalance(),
                entity.getCumulativeSpend());
    }

    private static PointTransaction toDomain(PointTransactionEntity entity) {
        return new PointTransaction(entity.getId(), entity.getUserId(), entity.getDelta(),
                entity.getBalanceAfter(), PointReason.valueOf(entity.getReason()),
                entity.getRefNo(), entity.getCreatedAt());
    }
}
