package com.flashsale.application.port.out;

import com.flashsale.application.port.out.SearchIndexMetrics;

/** 搜尋索引的對帳指標。 */
public interface SearchIndexMetrics {

    void recordReconciliation(long missingCount, long orphanedCount);
}
