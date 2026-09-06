package com.flashsale.domain.activity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 秒殺活動的上架狀態，與時間窗口正交：只有 ONLINE 且落在時間窗口內才可搶購。 */
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
