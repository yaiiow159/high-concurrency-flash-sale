package com.flashsale.application.port.out;

import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.notification.NotificationChannel;

import java.util.List;
import java.util.Optional;

/** 通知持久化埠（出站）。 */
public interface NotificationRepository {

    /**
     * 建立通知；同一個來源事件在同一個管道已有紀錄時不重複建立。
     *
     * <p><b>回傳 Optional 而非拋例外</b>：呼叫端是 MQ 消費端，
     * 而 Outbox 是至少一次語意——重複投遞是常態不是異常。
     * 與 {@code OrderRepository.saveIfAbsent} 同一個手法。
     *
     * @return 本次真的建立時回傳通知；已存在則回傳 {@code Optional.empty()}
     */
    Optional<Notification> saveIfAbsent(Notification notification);

    Notification update(Notification notification);

    Optional<Notification> findById(Long id);

    /** 某使用者的站內信，新到舊。 */
    List<Notification> findInAppByUserId(Long userId, int limit, int offset);

    long countUnread(Long userId);

    /**
     * 待寄送的通知，供排程撈取。
     *
     * <p>含 {@code FAILED}——寄信失敗最常見的成因是 SMTP 暫時故障，
     * 重試就會成功。只撈 {@code PENDING} 等於讓一次網路抖動永久吞掉一封通知。
     */
    List<Notification> findAwaitingDelivery(NotificationChannel channel, int maxAttempts, int limit);
}
