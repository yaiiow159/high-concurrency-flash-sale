package com.flashsale.application.service;

import com.flashsale.application.port.in.MembershipReconciliationUseCase;
import com.flashsale.application.port.in.dto.PointBalanceReconciliation;
import com.flashsale.application.port.out.MembershipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 積分對帳：流水加總是否等於餘額。
 *
 * <h2>為什麼需要它</h2>
 *
 * <p>ADR-0016 的「後果」欄裡寫著這件事：餘額是快照、流水才是真實來源，
 * 兩者在同一個交易內更新，因此<b>正常路徑不會分岔</b>。
 *
 * <p>但「正常路徑不會分岔」不等於「不會分岔」。任何繞過
 * {@code MembershipService} 直接寫流水的東西——維運手動 SQL、
 * 未來的資料遷移、一個寫錯的批次——都會讓餘額失準，而<b>沒有任何東西會發現</b>。
 * 積分是資產，一個沒有人發現的資產誤差就是一個沒有人發現的財務問題。
 *
 * <h2>只讀不修</h2>
 *
 * <p>與 {@code InventoryReconciliationService} 同一個立場：
 * 這裡的偏差本身就代表<b>有東西繞過了正規路徑</b>，
 * 此時「自動修正」等於用一個猜測覆蓋另一個猜測。
 *
 * <p>而且兩個方向都可能是對的：餘額多了可能是流水漏寫，
 * 也可能是有人直接改了餘額。分不出來的時候不該動手。
 */
@Service
public class MembershipReconciliationService implements MembershipReconciliationUseCase {

    private static final Logger log = LoggerFactory.getLogger(MembershipReconciliationService.class);

    private final MembershipRepository membershipRepository;

    public MembershipReconciliationService(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    /**
     * 對帳。
     *
     * <p><b>只回不平的帳戶</b>，帳平的不佔回應——與全量庫存對帳同一個做法。
     * 回一整份「全部正常」的清單，會讓真正的問題埋在幾萬列裡。
     */
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
