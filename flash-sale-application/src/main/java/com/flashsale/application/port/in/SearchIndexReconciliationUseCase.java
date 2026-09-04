package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.SearchIndexReconciliation;

/**
 * 搜尋索引對帳。
 *
 * <p>索引與資料庫分岔時<b>沒有任何東西會報錯</b>——搜尋照樣回 200，
 * 只是結果是錯的。這是讀模型的固有代價，而唯一能主動發現它的就是對帳。
 */
public interface SearchIndexReconciliationUseCase {

    /**
     * 比對索引與資料庫。
     *
     * @param repair 是否順手修掉差異
     */
    SearchIndexReconciliation reconcile(boolean repair);
}
