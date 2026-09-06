package com.flashsale.domain.notification;

/** 通知管道。 */
public enum NotificationChannel {

    /** 站內信。 */
    IN_APP,

    /** 電子郵件。 */
    EMAIL;

    /** 這個管道需要經過外部系統嗎？決定它建立時是 PENDING 還是 SENT。 */
    public boolean requiresDelivery() {
        return this == EMAIL;
    }
}
