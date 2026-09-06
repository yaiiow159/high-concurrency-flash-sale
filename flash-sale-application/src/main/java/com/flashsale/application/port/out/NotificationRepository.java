package com.flashsale.application.port.out;

import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.notification.NotificationChannel;

import java.util.List;
import java.util.Optional;

/** 通知持久化埠（出站）。 */
public interface NotificationRepository {

    /** 建立通知；同一個來源事件在同一個管道已有紀錄時不重複建立。 */
    Optional<Notification> saveIfAbsent(Notification notification);

    Notification update(Notification notification);

    Optional<Notification> findById(Long id);

    /** 某使用者的站內信，新到舊。 */
    List<Notification> findInAppByUserId(Long userId, int limit, int offset);

    long countUnread(Long userId);

    /** 某使用者<b>未讀</b>的站內信。 */
    List<Notification> findUnreadInApp(Long userId, int limit);

    /** 待寄送的通知，供排程撈取。 */
    List<Notification> findAwaitingDelivery(NotificationChannel channel, int maxAttempts, int limit);
}
