package com.flashsale.application.service;

import com.flashsale.application.port.in.MembershipReconciliationUseCase;
import com.flashsale.application.port.in.dto.PointBalanceReconciliation;
import com.flashsale.application.port.out.MembershipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 積分對帳：流水加總是否等於餘額。 */
@Service
public class MembershipReconciliationService implements MembershipReconciliationUseCase {

    private static final Logger log = LoggerFactory.getLogger(MembershipReconciliationService.class);

    private final MembershipRepository membershipRepository;

    public MembershipReconciliationService(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    /** 對帳。 */
    @Override
    @Transactional(readOnly = true)
    public PointBalanceReconciliation reconcile() {
        List<PointBalanceReconciliation.Drift> drifts =
                membershipRepository.findBalanceDrifts().stream()
                        .map(drift -> new PointBalanceReconciliation.Drift(
                                drift.userId(), drift.ledgerSum(), drift.balance(),
                                drift.balance() - drift.ledgerSum()))
                        .toList();

        if (!drifts.isEmpty()) {
            // 積分是資產，而一個沒有人發現的資產誤差就是一個沒有人發現的財務問題。
            // 用 warn 而非 info：這一行應該接到告警，不是躺在日誌裡
            log.warn("積分對帳發現 {} 個帳戶的餘額與流水不符", drifts.size());
        }
        return PointBalanceReconciliation.of(drifts);
    }
}
