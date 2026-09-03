package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.NotificationView;

import java.util.List;

/** 使用者讀取自己的站內信。 */
public interface NotificationUseCase {

    List<NotificationView> listForUser(Long userId, int page, int size);

    /**
     * 未讀數。
     *
     * <p>單獨一個端點而不是塞在列表回應裡：導覽列上的紅點需要它，
     * 而那一頁通常不會同時載入整份通知列表。
     */
    long unreadCount(Long userId);

    /** 標記已讀。重複標記不視為錯誤——兩個分頁同時開著是正常操作。 */
    NotificationView markRead(Long notificationId, Long userId);

    /** 全部標記已讀。 */
    int markAllRead(Long userId);
}
