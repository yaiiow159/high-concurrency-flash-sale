package com.flashsale.domain.inventory;

/**
 * 庫存異動種類。
 *
 * <p>增減的方向由流水自己的 {@code availableDelta} / {@code allocatedDelta} 表達，
 * 這個列舉回答的是另一個問題：<b>為什麼</b>會有這筆異動。
 * 「這 37 件是怎麼消失的」需要的是原因，不只是數字。
 */
public enum InventoryMovementType {

    /** 一般下單扣減可售量。 */
    DEDUCT,

    /** 取消或退貨退回可售量。 */
    RESTORE,

    /** 劃撥給秒殺活動：可售量搬到劃撥量，總量不變。 */
    ALLOCATE,

    /** 活動結束釋放：劃撥量歸零，未售出的部分回到可售量。 */
    RELEASE,

    /** 人工調整（盤點、補貨、期初建帳）。 */
    ADJUST
}
