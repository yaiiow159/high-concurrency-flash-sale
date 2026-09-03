package com.flashsale.application.service;

import com.flashsale.application.port.out.NotificationRepository;
import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.notification.NotificationChannel;
import com.flashsale.domain.notification.NotificationType;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("站內信查詢")
class NotificationQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
    private static final long USER = 25L;

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationQueryService service;

    @BeforeEach
    void setUp() {
        service = new NotificationQueryService(notificationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(notificationRepository.update(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static Notification unread(int index) {
        return Notification.compose(USER, NotificationChannel.IN_APP,
                NotificationType.ORDER_PAID, "標題 " + index, "內容", "ORD-" + index,
                "evt-" + index, NOW.minusSeconds(index));
    }

    @Test
    @DisplayName("全部標為已讀查的是未讀，不是最新的一頁——否則舊的未讀永遠標不到")
    void marksUnreadNotTheFirstPage() {
        // 實測踩過：60 筆通知、最舊的 10 筆未讀，
        // 用列表查詢實作只看最新 50 筆，回報 marked: 0 而紅點永遠清不掉
        List<Notification> oldUnread = IntStream.range(0, 10)
                .mapToObj(NotificationQueryServiceTest::unread)
                .toList();
        when(notificationRepository.findUnreadInApp(eq(USER), anyInt())).thenReturn(oldUnread);

        int marked = service.markAllRead(USER);

        assertThat(marked).isEqualTo(10);
        // 這一條是重點：絕不能用分頁列表來實作「全部標為已讀」
        verify(notificationRepository, never()).findInAppByUserId(any(), anyInt(), anyInt());
        assertThat(oldUnread).allSatisfy(n -> assertThat(n.isUnread()).isFalse());
    }

    @Test
    @DisplayName("沒有未讀時回 0，且不寫入任何一筆")
    void nothingToMark() {
        when(notificationRepository.findUnreadInApp(eq(USER), anyInt())).thenReturn(List.of());

        assertThat(service.markAllRead(USER)).isZero();
        verify(notificationRepository, never()).update(any());
    }

    @Test
    @DisplayName("別人的通知回「不存在」而非「無權限」——後者等於確認這個 ID 有效")
    void otherUsersNotificationLooksMissing() {
        Notification other = Notification.compose(USER + 1, NotificationChannel.IN_APP,
                NotificationType.ORDER_PAID, "標題", "內容", "ORD-1", "evt-x", NOW);
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.markRead(1L, USER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("頁大小夾在上限——這是登入後可無限次呼叫的端點")
    void clampsPageSize() {
        when(notificationRepository.findInAppByUserId(eq(USER), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.listForUser(USER, 0, 99999);

        verify(notificationRepository).findInAppByUserId(USER, 50, 0);
    }
}
