package com.flashsale.application.service;

import com.flashsale.application.port.in.NotificationUseCase;
import com.flashsale.application.port.in.dto.NotificationView;
import com.flashsale.application.port.out.NotificationRepository;
import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.Page;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 站內信查詢與已讀標記。 */
@Service
public class NotificationQueryService implements NotificationUseCase {

    private static final int MAX_PAGE_SIZE = 50;

    /** 「全部標為已讀」單次處理的上限。 */
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
        Page paging = Page.of(page, size, MAX_PAGE_SIZE);
        return notificationRepository
                .findInAppByUserId(userId, paging.size(), paging.offset()).stream()
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

    /** 全部標記已讀。 */
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
