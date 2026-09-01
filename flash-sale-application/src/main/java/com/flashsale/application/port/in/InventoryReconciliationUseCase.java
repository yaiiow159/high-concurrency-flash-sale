package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.SkuReconciliation;

import java.util.List;

/**
 * 一般庫存對帳：核對庫存數字與異動流水是否一致。
 *
 * <p>與秒殺對帳分開，因為兩者比對的東西不同、失效的方式也不同。
 * 硬併成一個介面只會得到一堆用不到的欄位。
 */
public interface InventoryReconciliationUseCase {

    /** 掃過所有 SKU，<b>只回傳不平的</b>。帳平的結果交給指標，不佔回傳值。 */
    List<SkuReconciliation> reconcileAll();

    /** 對單一 SKU 對帳，供維運查證。 */
    SkuReconciliation reconcile(Long skuId);
}
