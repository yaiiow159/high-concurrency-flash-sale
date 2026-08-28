package com.flashsale.domain.activity;

/** 秒殺活動的上架狀態，與時間窗口正交：只有 ONLINE 且落在時間窗口內才可搶購。 */
public enum ActivityStatus {
    /** 草稿，僅營運可見。 */
    DRAFT,
    /** 已上架，開放搶購（仍受時間窗口限制）。 */
    ONLINE,
    /** 已下架，任何時間都不可搶購。 */
    OFFLINE
}
