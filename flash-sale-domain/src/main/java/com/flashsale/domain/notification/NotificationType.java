package com.flashsale.domain.notification;

/** 通知類型。 */
public enum NotificationType {

    /** 付款成功。使用者需要知道錢收到了、接下來等出貨。 */
    ORDER_PAID,

    /** 已出貨。改變了預期——從「等出貨」變成「等收貨」，而且開始能追蹤物流。 */
    ORDER_SHIPPED,

    /** 已送達。是鑑賞期的起算點，使用者需要知道時間開始跑。 */
    ORDER_COMPLETED,

    /** 訂單已取消。多半是逾時未付款，使用者可能還在等著付款。 */
    ORDER_CANCELLED,

    /** 退款已送出。錢什麼時候會回到帳上是退貨流程裡最常被問的一件事。 */
    REFUND_SENT,

    /** 訂閱的商品補貨了。 */
    RESTOCKED
}
