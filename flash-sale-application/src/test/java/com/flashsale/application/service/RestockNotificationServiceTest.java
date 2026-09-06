package com.flashsale.application.service;

import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.out.NotificationRepository;
import com.flashsale.application.port.out.RestockSubscriptionRepository;
import com.flashsale.application.port.out.RestockSubscriptionRepository.Pending;
import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("到貨通知")
class RestockNotificationServiceTest {

    /** 與 RestockNotificationService.MAX_NOTIFY_PER_RESTOCK 一致。 */
    private static final int MAX_NOTIFY = 500;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);

    private RestockSubscriptionRepository subscriptions;
    private NotificationRepository notifications;
    private CatalogQueryUseCase catalog;
    private RestockNotificationService.Notifier notifier;
    private RestockNotificationService service;

    @BeforeEach
    void setUp() {
        subscriptions = mock(RestockSubscriptionRepository.class);
        notifications = mock(NotificationRepository.class);
        catalog = mock(CatalogQueryUseCase.class);
        // 交易邊界在 Notifier 上（拆成獨立 Bean 才會經過代理，鐵則 6），
        // 所以測試也要照真實的組裝方式接起來
        notifier = new RestockNotificationService.Notifier(
                subscriptions, notifications, catalog, CLOCK);
        service = new RestockNotificationService(subscriptions, catalog, notifier, CLOCK);
        when(catalog.findSkus(any())).thenReturn(List.of(new CatalogQueryUseCase.SkuLookup(
                7L, 1L, "測試商品", "規格", BigDecimal.TEN, true)));
    }

    @Nested
    @DisplayName("訂閱")
    class Subscribe {

        @Test
        @DisplayName("不存在的 SKU 擋下")
        void rejectsUnknownSku() {
            when(catalog.findSkus(any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.subscribe(1L, 999L))
                    .isInstanceOf(BusinessException.class);
            verify(subscriptions, never()).subscribe(any(), any(), any());
        }

        @Test
        @DisplayName("超過同時訂閱上限時擋下")
        void enforcesPerUserCap() {
            // 沒有上限的話，一個腳本可以把整個目錄都訂閱起來，
            // 之後任何一次補貨都會把他變成一場信件風暴的來源
            when(subscriptions.countPending(1L)).thenReturn(50L);

            assertThatThrownBy(() -> service.subscribe(1L, 7L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("最多");
        }

        @Test
        @DisplayName("正常訂閱")
        void subscribes() {
            when(subscriptions.countPending(1L)).thenReturn(3L);

            service.subscribe(1L, 7L);

            verify(subscriptions).subscribe(1L, 7L, CLOCK.instant());
        }
    }

    @Nested
    @DisplayName("補貨通知")
    class Notify {

        @Test
        @DisplayName("沒有人在等就什麼都不做")
        void noWaitersNoWork() {
            when(subscriptions.findWaitersFor(anyLong(), anyInt())).thenReturn(List.of());

            assertThat(service.notifyWaiters(7L)).isZero();
            verify(subscriptions, never()).markNotified(any(), any());
        }

        @Test
        @DisplayName("先標記已通知，再寫通知")
        void marksBeforeComposing() {
            // 反過來的話，寫到一半失敗重跑時前面那些人會再收到一次，
            // 而重複的到貨通知比漏掉一次更讓人惱火
            when(subscriptions.findWaitersFor(anyLong(), anyInt()))
                    .thenReturn(List.of(new Pending(11L, 1L), new Pending(12L, 2L)));
            when(subscriptions.markNotified(any(), any())).thenReturn(2);

            assertThat(service.notifyWaiters(7L)).isEqualTo(2);

            InOrder order = Mockito.inOrder(subscriptions, notifications);
            order.verify(subscriptions).markNotified(List.of(11L, 12L), CLOCK.instant());
            order.verify(notifications, Mockito.times(2)).saveIfAbsent(any());
        }

        @Test
        @DisplayName("標記到 0 筆代表別人先做了，不再重複發")
        void skipsWhenAnotherNodeWon() {
            when(subscriptions.findWaitersFor(anyLong(), anyInt()))
                    .thenReturn(List.of(new Pending(11L, 1L)));
            when(subscriptions.markNotified(any(), any())).thenReturn(0);

            assertThat(service.notifyWaiters(7L)).isZero();
            verify(notifications, never()).saveIfAbsent(any());
        }

        @Test
        @DisplayName("每筆訂閱只會產生一則通知，來源事件 id 帶訂閱 id")
        void oneNotificationPerSubscription() {
            when(subscriptions.findWaitersFor(anyLong(), anyInt()))
                    .thenReturn(List.of(new Pending(11L, 1L)));
            when(subscriptions.markNotified(any(), any())).thenReturn(1);

            service.notifyWaiters(7L);

            ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
            verify(notifications).saveIfAbsent(saved.capture());
            // 消費端的 saveIfAbsent 靠它擋重複
            assertThat(saved.getValue().sourceEventId()).isEqualTo("restock-11");
            assertThat(saved.getValue().body()).contains("測試商品");
        }

        @Test
        @DisplayName("一個 SKU 失敗不會拖垮整輪掃描")
        void oneFailingSkuDoesNotStopTheScan() {
            // 先前整輪包在一個交易裡，一筆爆掉就全部回滾——
            // 而毒藥列會讓每一輪都在同一個地方失敗，到貨通知等於永久停擺
            when(subscriptions.findRestockedSkuIds(anyInt())).thenReturn(List.of(7L, 8L));
            when(subscriptions.findWaitersFor(7L, MAX_NOTIFY))
                    .thenThrow(new RuntimeException("boom"));
            when(subscriptions.findWaitersFor(8L, MAX_NOTIFY))
                    .thenReturn(List.of(new Pending(11L, 1L)));
            when(subscriptions.markNotified(any(), any())).thenReturn(1);

            assertThat(service.notifyAllRestocked()).isEqualTo(1);
        }

        @Test
        @DisplayName("掃描會逐個補貨的 SKU 通知")
        void scanNotifiesEachRestockedSku() {
            when(subscriptions.findRestockedSkuIds(anyInt())).thenReturn(List.of(7L, 8L));
            when(subscriptions.findWaitersFor(anyLong(), anyInt()))
                    .thenReturn(List.of(new Pending(11L, 1L)));
            when(subscriptions.markNotified(any(), any())).thenReturn(1);

            assertThat(service.notifyAllRestocked()).isEqualTo(2);
        }
    }
}
