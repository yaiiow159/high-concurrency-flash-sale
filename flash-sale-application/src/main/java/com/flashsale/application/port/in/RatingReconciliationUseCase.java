package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.RatingReconciliation;

/** 評分聚合對帳：{@code product_rating} 是否等於 {@code review} 表的真實統計。 */
public interface RatingReconciliationUseCase {

    /**
     * @param repair {@code true} 時把聚合重算成 {@code review} 表的真實統計
     */
    RatingReconciliation reconcile(boolean repair);
}
