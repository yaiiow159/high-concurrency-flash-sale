package com.flashsale.domain.aftersales;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 退貨單狀態機（ADR-0011）。 */
public enum ReturnStatus {

    /** 買家已申請，等待審核。 */
    REQUESTED,

    /** 已核准。需寄回者等待買家寄出，免寄回者可直接退款。 */
    APPROVED,

    /** 已收到退回品並完成驗收。是否可再售已在此刻決定。 */
    RECEIVED,

    /** 已退款（終態）。錢已退、庫存已依驗收結果處理。 */
    REFUNDED,

    /** 審核未通過（終態）。 */
    REJECTED,

    /** 買家自行撤回（終態）。 */
    CANCELLED;

    private static final Map<ReturnStatus, Set<ReturnStatus>> ALLOWED_TRANSITIONS = Map.of(
            REQUESTED, EnumSet.of(APPROVED, REJECTED, CANCELLED),
            // 核准後仍可由買家撤回——貨還沒寄出，撤回不會留下任何殘局。
            // 直接到 REFUNDED 的那條只在免寄回時開放，由聚合根另外把關
            APPROVED, EnumSet.of(RECEIVED, REFUNDED, CANCELLED),
            // 貨已經收下了就不能再撤回：東西在賣家手上，
            // 撤回會讓買家既沒錢也沒貨
            RECEIVED, EnumSet.of(REFUNDED),
            REFUNDED, Collections.emptySet(),
            REJECTED, Collections.emptySet(),
            CANCELLED, Collections.emptySet()
    );

    public boolean canTransitionTo(ReturnStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public boolean isFinal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /** 此狀態是否仍佔用著訂單行的可退數量。 */
    public boolean holdsReturnQuota() {
        return this == REQUESTED || this == APPROVED
                || this == RECEIVED || this == REFUNDED;
    }
}
