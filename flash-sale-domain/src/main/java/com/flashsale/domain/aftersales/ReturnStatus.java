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

    /**
     * 已核可退款、錢還沒出去。
     *
     * <p>這個狀態存在的理由是誠實：閘道退款是外部呼叫，而它成功與否要等回覆才知道。
     * 少了它，退貨單在發起的當下就寫成 REFUNDED，閘道失敗時帳上說已退、錢卻沒送出，
     * 而且沒有任何查詢找得出這種單子（ADR-0031）。
     */
    REFUNDING,

    /** 已退款（終態）。錢已退、庫存已依驗收結果處理。 */
    REFUNDED,

    /** 審核未通過（終態）。 */
    REJECTED,

    /** 買家自行撤回（終態）。 */
    CANCELLED;

    private static final Map<ReturnStatus, Set<ReturnStatus>> ALLOWED_TRANSITIONS = Map.of(
            REQUESTED, EnumSet.of(APPROVED, REJECTED, CANCELLED),
            // 核准後仍可由買家撤回——貨還沒寄出，撤回不會留下任何殘局。
            // 直接到 REFUNDING 的那條只在免寄回時開放，由聚合根另外把關
            APPROVED, EnumSet.of(RECEIVED, REFUNDING, CANCELLED),
            // 貨已經收下了就不能再撤回：東西在賣家手上，
            // 撤回會讓買家既沒錢也沒貨
            RECEIVED, EnumSet.of(REFUNDING),
            // 沒有 REFUNDING → 失敗的那條路。已核可的退款只能往前推到成功，
            // 回頭當作沒發生的話，買家的貨已經退了卻拿不到錢
            REFUNDING, EnumSet.of(REFUNDED),
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
                || this == RECEIVED || this == REFUNDING || this == REFUNDED;
    }

    /** 錢是否還沒真的出去。停留太久代表閘道那一步卡住了，要有人或排程去推。 */
    public boolean awaitingSettlement() {
        return this == REFUNDING;
    }
}
