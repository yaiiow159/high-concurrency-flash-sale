package com.flashsale.domain.aftersales;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 退貨單狀態機（ADR-0011）。
 *
 * <pre>
 *   REQUESTED ──approve()──▶ APPROVED ──receive()──▶ RECEIVED ──refund()──▶ REFUNDED (終態)
 *       │                        │                                             ▲
 *       │                        └────────── 免寄回時直接退款 ─────────────────┘
 *       │
 *       ├──reject()──▶ REJECTED  (終態)
 *       └──cancel()──▶ CANCELLED (終態，買家自行撤回)
 * </pre>
 *
 * <h2>為什麼 {@code APPROVED} 有兩條出路</h2>
 *
 * <p>未出貨的訂單根本沒有貨要寄回——商品還在倉庫裡。
 * 強迫它經過 {@code RECEIVED}，就得憑空捏造一次「收到退回品」，
 * 那個時間戳記會是假的，而假資料遲早會被某份報表當真。
 *
 * <p>是否需要寄回由退貨單建立時的訂單狀態決定，寫死在聚合根裡；
 * 呼叫端不能自己選，否則「已出貨卻宣稱免寄回」就成了免費拿貨的漏洞。
 */
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

    /**
     * 此狀態是否仍佔用著訂單行的可退數量。
     *
     * <p>用於「累計已退數量」的計算——這是防重複退款的第二層。
     * <b>進行中的退貨單也要算進去</b>：若只算 {@code REFUNDED}，
     * 買家可以在第一張單還在審核時開第二張，兩張都退。
     *
     * <p>反過來，{@code REJECTED} 與 {@code CANCELLED} 必須釋放額度，
     * 否則被駁回一次的商品就永遠不能再申請了。
     */
    public boolean holdsReturnQuota() {
        return this == REQUESTED || this == APPROVED
                || this == RECEIVED || this == REFUNDED;
    }
}
