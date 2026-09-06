package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.PointBalanceReconciliation;

/** 積分對帳：流水加總是否等於餘額。 */
public interface MembershipReconciliationUseCase {

    PointBalanceReconciliation reconcile();
}
