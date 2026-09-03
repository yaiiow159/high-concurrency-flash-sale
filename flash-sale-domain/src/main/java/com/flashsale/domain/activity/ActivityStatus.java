package com.flashsale.domain.activity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 秒殺活動的上架狀態，與時間窗口正交：只有 ONLINE 且落在時間窗口內才可搶購。
 *
 * <pre>
 *   DRAFT ──上架──▶ ONLINE ◀──重新上架──┐
 *                      │                │
 *                      └───下架────▶ OFFLINE
 * </pre>
 *
 * <p><b>DRAFT 不能直接下架</b>：它從來沒上架過，「下架」對它沒有意義。
 * 允許這條轉移只會讓「OFFLINE」同時代表兩件事——
 * 「曾經賣過但收掉了」與「根本沒開始過」——而那兩者的營運處理完全不同。
 */
public enum ActivityStatus {
    /** 草稿，僅營運可見。 */
    DRAFT,
    /** 已上架，開放搶購（仍受時間窗口限制）。 */
    ONLINE,
    /** 已下架，任何時間都不可搶購。 */
    OFFLINE;

    private static final Map<ActivityStatus, Set<ActivityStatus>> ALLOWED_TRANSITIONS = Map.of(
            DRAFT, EnumSet.of(ONLINE),
            ONLINE, EnumSet.of(OFFLINE),
            // 下架後可以重新上架。誤下架是真實會發生的操作失誤，
            // 不給回頭路只會逼營運去直接改資料庫——那才是更危險的事。
            //
            // 庫存已釋放的活動重新上架不會復活庫存：
            // 預熱那一側有 requireNotAlreadyReleased 擋著（ADR-0008）。
            OFFLINE, EnumSet.of(ONLINE)
    );

    public boolean canTransitionTo(ActivityStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(target);
    }
}
