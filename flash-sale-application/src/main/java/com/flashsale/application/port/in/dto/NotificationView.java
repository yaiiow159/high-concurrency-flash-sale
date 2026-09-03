package com.flashsale.application.port.in.dto;

import com.flashsale.domain.notification.Notification;

import java.time.Instant;

/**
 * 站內信的對外表述。
 *
 * <p><b>不含 {@code recipient} 與 {@code failureReason}。</b>
 * 前者是寄送紀錄（使用者本來就知道自己的信箱），
 * 後者是 SMTP 的錯誤字串，可能帶著伺服器主機名之類的內部資訊。
 * 兩者都屬於維運要看的東西，不屬於這個端點。
 */
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
