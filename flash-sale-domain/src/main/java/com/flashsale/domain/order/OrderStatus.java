package com.flashsale.domain.order;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 訂單狀態機。
 *
 * <p>合法轉移集中宣告在此處，而非散落在各個 service 的 if-else 中——
 * 新增狀態時只需改這張表，編譯器與單元測試會找出所有受影響處。
 *
 * <pre>
 *   PENDING_PAYMENT ──pay()────────▶ PAID        (終態)
 *          │
 *          ├────────cancel()───────▶ CANCELLED   (終態，觸發庫存補償)
 *          └────────markFailed()──▶ FAILED       (終態，觸發庫存補償)
 * </pre>
 */
public enum OrderStatus {

    /** 已建立、待付款。庫存已於 Redis 預扣，等待使用者付款。 */
    PENDING_PAYMENT,

    /** 已付款，交易完成。 */
    PAID,

    /** 已取消（逾時未付款或使用者主動取消），需補償預扣庫存。 */
    CANCELLED,

    /** 建單流程異常終止（例如重試耗盡後進入 DLQ），需補償預扣庫存。 */
    FAILED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING_PAYMENT, EnumSet.of(PAID, CANCELLED, FAILED),
            PAID, Collections.emptySet(),
            CANCELLED, Collections.emptySet(),
            FAILED, Collections.emptySet()
    );

    public boolean canTransitionTo(OrderStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** 終態不可再變更，且不會重複觸發補償。 */
    public boolean isFinal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /** 此狀態是否代表「庫存需要退回」。 */
    public boolean requiresStockCompensation() {
        return this == CANCELLED || this == FAILED;
    }
}
