package com.flashsale.application.port.in.dto;

import com.flashsale.domain.notification.Notification;

import java.time.Instant;

/** 站內信的對外表述。 */
public record NotificationView(
        Long notificationId,
        String type,
        String title,
        String body,
        /** 關聯的訂單號或退貨單號，供畫面連回去。 */
        String referenceNo,
        boolean unread,
        Instant createdAt
) {

    public static NotificationView from(Notification notification) {
        return new NotificationView(
                notification.id(),
                notification.type().name(),
                notification.title(),
                notification.body(),
                notification.referenceNo(),
                notification.isUnread(),
                notification.createdAt());
    }
}
