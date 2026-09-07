package com.flashsale.application.port.in;

import java.time.Duration;

/** 補送卡住的退款（ADR-0031）。 */
public interface RefundRecoveryUseCase {

    /**
     * 重推已核可但錢還沒出去的退款。
     *
     * <p>退款的送達不能只靠佇列重試——重試預算耗盡後訊息進死信，
     * 而退貨單早已 commit。這裡以資料庫裡的 REFUNDING 狀態作為工作項，
     * 讓補送的次數不受任何預算限制。
     *
     * @param settlementGrace 發起後多久還沒到帳才算卡住。必須明顯長於
     * 消費端的重試預算，否則會與正常重試搶著呼叫閘道
     * @return 本輪推成功的筆數
     */
    int recoverStuckRefunds(Duration settlementGrace, int batchSize);
}
