package com.flashsale.application.service;

import com.flashsale.application.port.in.NotificationUseCase;
import com.flashsale.application.port.in.dto.NotificationView;
import com.flashsale.application.port.out.NotificationRepository;
import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 站內信查詢與已讀標記。 */
@Service
public class NotificationQueryService implements NotificationUseCase {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 「全部標為已讀」單次處理的上限。
     *
     * <p>刻意不做批次迴圈：在同一個交易裡重查未讀，JPA 的變更還沒 flush，
     * 第二批會撈到同一群人，於是無限迴圈。取一個實務上足夠大的上限，
     * 超過的部分由下一次按鈕處理——而回傳的筆數會讓使用者看得出還有沒有剩。
     */
    private static final int MAX_MARK_ALL = 1000;

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public NotificationQueryService(NotificationRepository notificationRepository, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationView> listForUser(Long userId, int page, int size) {
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.clamp(size, 1, MAX_PAGE_SIZE);
        return notificationRepository
                .findInAppByUserId(userId, pageSize, Math.max(page, 0) * pageSize).stream()
                .map(NotificationView::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countUnread(userId);
    }

    @Override
    @Transactional
    public NotificationView markRead(Long notificationId, Long userId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND,
                        "通知不存在: " + notificationId));
        if (!notification.belongsTo(userId)) {
            // 回「不存在」而非「無權限」：後者等於確認這個 ID 是有效的
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND,
                    "通知不存在: " + notificationId);
        }
        notification.markRead(clock.instant());
        return NotificationView.from(notificationRepository.update(notification));
    }

    /**
     * 全部標記已讀。
     *
     * <p><b>查的是「未讀的」而不是「最新的一頁」。</b>
     * 先前用列表查詢實作，於是只處理最新的 50 筆——而未讀的那些
     * 可能全都比那一頁更舊。實測：60 筆通知、最舊的 10 筆未讀，
     * 按下去回報 {@code marked: 0}，紅點永遠清不掉，
     * 而且再按幾次都一樣。
     *
     * <p>逐筆處理而非一句批次 UPDATE：批次會繞過聚合根的
     * {@code markRead}——那裡擋著「只有站內信有已讀狀態」這條規則。
     * 把那條規則複製進 SQL 的 WHERE 子句，就變成兩個真實來源。
     */
    @Override
    @Transactional
    public int markAllRead(Long userId) {
        Instant now = clock.instant();
        List<Notification> unread =
                notificationRepository.findUnreadInApp(userId, MAX_MARK_ALL);
        for (Notification notification : unread) {
            notification.markRead(now);
            notificationRepository.update(notification);
        }
        return unread.size();
    }
}
