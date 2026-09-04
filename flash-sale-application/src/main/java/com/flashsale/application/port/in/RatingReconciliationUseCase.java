package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.RatingReconciliation;

/**
 * 評分聚合對帳：{@code product_rating} 是否等於 {@code review} 表的真實統計。
 *
 * <p>ADR-0014 的「後果」欄寫著需要它：兩者在同一個交易內更新，
 * 因此正常路徑不會分岔——但任何繞過 {@code ReviewService} 直接寫
 * {@code review} 表的東西都會讓聚合失準，而<b>沒有任何東西會發現</b>。
 *
 * <p><b>可以自動修復</b>，這一點與積分和庫存不同。理由是真實來源明確：
 * {@code review} 表是原始事實，聚合只是它的統計。重算不需要任何猜測——
 * 而積分的偏差分不出是「流水漏寫」還是「有人改了餘額」，庫存更是如此。
 *
 * <p>預設仍然關閉。看過差異再決定要不要修，是唯一安全的順序。
 */
public interface RatingReconciliationUseCase {

    /**
     * @param repair {@code true} 時把聚合重算成 {@code review} 表的真實統計
     */
    RatingReconciliation reconcile(boolean repair);
}
