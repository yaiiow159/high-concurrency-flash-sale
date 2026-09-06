package com.flashsale.domain.fulfillment;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 出貨狀態機。 */
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
