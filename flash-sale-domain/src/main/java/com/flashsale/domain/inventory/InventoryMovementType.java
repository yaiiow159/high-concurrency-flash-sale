package com.flashsale.domain.inventory;

/** 庫存異動種類。 */
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
