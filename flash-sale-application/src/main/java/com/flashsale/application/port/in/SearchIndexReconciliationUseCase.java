package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.SearchIndexReconciliation;

/** 搜尋索引對帳。 */
public interface SearchIndexReconciliationUseCase {

    /**
     * 比對索引與資料庫。
     *
     * @param repair 是否順手修掉差異
     */
    SearchIndexReconciliation reconcile(boolean repair);
}
