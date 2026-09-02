package com.flashsale.domain.fulfillment;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 出貨狀態機。
 *
 * <pre>
 *   READY ──dispatch()──▶ IN_TRANSIT ──deliver()───▶ DELIVERED  (終態)
 *     │                       │
 *     │                       └──markFailed()────▶ FAILED      (可重新派送)
 *     └──cancel()──────────────────────────────▶ CANCELLED  (終態)
 *
 *   FAILED ──redispatch()──▶ IN_TRANSIT
 * </pre>
 *
 * <h2>為什麼 FAILED 不是終態</h2>
 *
 * <p>配送失敗在現實中極常見（收件人不在、地址寫錯、超商滿櫃），
 * 而後續幾乎都是<b>重新派送</b>而非取消訂單。若把 FAILED 設成終態，
 * 每一次「明天再送一次」都得先取消再建一張新的出貨單——
 * 那會讓同一批貨在系統裡留下兩筆紀錄，物流單號也對不起來。
 *
 * <p>這與訂單狀態機刻意把終態鎖死是不同的取捨：訂單的終態牽涉金流與庫存，
 * 回頭一次就可能多退一次錢；出貨失敗只是「東西還在路上」，
 * 重試不會產生任何不可逆的副作用。
 */
public enum ShipmentStatus {

    /** 已建立、等待揀貨出庫。 */
    READY,

    /** 已交付承運商，運送中。 */
    IN_TRANSIT,

    /** 已送達。 */
    DELIVERED,

    /** 配送失敗（收件人不在、地址錯誤⋯⋯），可重新派送。 */
    FAILED,

    /** 出貨前取消。 */
    CANCELLED;

    private static final Map<ShipmentStatus, Set<ShipmentStatus>> ALLOWED_TRANSITIONS = Map.of(
            READY, EnumSet.of(IN_TRANSIT, CANCELLED),
            IN_TRANSIT, EnumSet.of(DELIVERED, FAILED),
            // 配送失敗可以重送。這是與訂單狀態機最大的差別，理由見類別註解
            FAILED, EnumSet.of(IN_TRANSIT, CANCELLED),
            DELIVERED, Collections.emptySet(),
            CANCELLED, Collections.emptySet()
    );

    public boolean canTransitionTo(ShipmentStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public boolean isFinal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /** 貨是否已經離開倉庫。用來判斷訂單還能不能直接取消。 */
    public boolean hasLeftWarehouse() {
        return this == IN_TRANSIT || this == DELIVERED || this == FAILED;
    }
}
