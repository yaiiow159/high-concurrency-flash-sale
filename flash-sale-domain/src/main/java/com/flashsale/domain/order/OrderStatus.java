package com.flashsale.domain.order;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 訂單狀態機。合法轉移集中宣告在 {@code ALLOWED_TRANSITIONS}。 */
public enum OrderStatus {

    /** 已建立、待付款。庫存已於 Redis 預扣，等待使用者付款。 */
    PENDING_PAYMENT,

    /** 已付款。不可轉 CANCELLED：取消會退庫存卻不退錢。 */
    PAID,

    /** 已出貨。買家不能再直接取消，要退錢必須走退貨流程。 */
    SHIPPED,

    /** 已送達，訂單完成。 */
    COMPLETED,

    /** 全額退款完成（終態）。部分退款不改狀態——沒退的行還能出貨。 */
    REFUNDED,

    /** 已取消（逾時未付款或使用者主動取消），需補償預扣庫存。 */
    CANCELLED,

    /** 建單流程異常終止（例如重試耗盡後進入 DLQ），需補償預扣庫存。 */
    FAILED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING_PAYMENT, EnumSet.of(PAID, CANCELLED, FAILED),
            // 不可取消：那會退庫存卻不退錢，而逾時關單排程隨時可能踩到
            PAID, EnumSet.of(SHIPPED, REFUNDED),
            // 出貨後不可取消。要退錢必須走退貨（P3 的退款 Saga），
            // 因為此時貨在路上，庫存不能直接退回可售池
            SHIPPED, EnumSet.of(COMPLETED, REFUNDED),
            // 已完成仍可全額退款：七天鑑賞期是在送達之後才開始的
            COMPLETED, EnumSet.of(REFUNDED),
            REFUNDED, Collections.emptySet(),
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

    /** 此狀態下的訂單是否還佔用庫存，對帳用來算「已售出」。 */
    public boolean holdsStock() {
        return this == PENDING_PAYMENT || this == PAID
                || this == SHIPPED || this == COMPLETED
                || this == REFUNDED;
    }
}
