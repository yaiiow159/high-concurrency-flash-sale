package com.flashsale.application.service;

import com.flashsale.application.port.out.NotificationRepository;
import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.notification.NotificationChannel;
import com.flashsale.domain.notification.NotificationType;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.domain.order.event.OrderShippedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 由事件產生通知。
 *
 * <p>測試盯的是三件這一層自己負責的事：
 * <b>每個事件產生兩個管道各一筆</b>、
 * <b>冪等鍵帶著來源事件 ID</b>、
 * <b>內容在建立當下就算好並存成快照</b>。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("通知派送")
class NotificationDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final long USER = 77L;
    private static final String ORDER_NO = "220956648921890816";

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationDispatchService service;

    @BeforeEach
    void setUp() {
        service = new NotificationDispatchService(notificationRepository,
                new NotificationComposer(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(notificationRepository.saveIfAbsent(any()))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));
    }

    private static OrderPaidEvent paidEvent() {
        return new OrderPaidEvent("evt-paid-1", 1, ORDER_NO, USER,
                new BigDecimal("101700"), NOW);
    }

    @Nested
    @DisplayName("每個事件產生兩個管道各一筆")
    class BothChannels {

        @Test
        @DisplayName("站內信與 Email 各建一筆，因為兩者可能一邊成功一邊失敗")
        void createsOnePerChannel() {
            service.onOrderPaid(paidEvent());

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository, times(2)).saveIfAbsent(captor.capture());

            assertThat(captor.getAllValues())
                    .extracting(Notification::channel)
                    .containsExactlyInAnyOrder(
                            NotificationChannel.IN_APP, NotificationChannel.EMAIL);
        }

        @Test
        @DisplayName("冪等鍵是來源事件 ID，兩個管道共用同一個值")
        void carriesSourceEventId() {
            service.onOrderPaid(paidEvent());

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository, times(2)).saveIfAbsent(captor.capture());

            // 唯一索引是 (source_event_id, channel)：共用事件 ID 但分管道，
            // 才能讓 Email 重試時不被站內信的存在擋掉
            assertThat(captor.getAllValues())
                    .allSatisfy(notification ->
                            assertThat(notification.sourceEventId()).isEqualTo("evt-paid-1"));
        }

        @Test
        @DisplayName("已存在時不拋例外——重複投遞是常態，拋例外會讓訊息進 DLQ")
        void duplicateDeliveryIsSilentlySkipped() {
            // doReturn 而非 when(...)：後者會真的呼叫一次 saveIfAbsent，
            // 觸發 setUp 裡那個 Answer 並帶著 null 參數，於是 NPE
            doReturn(Optional.empty()).when(notificationRepository).saveIfAbsent(any());

            service.onOrderPaid(paidEvent());

            verify(notificationRepository, times(2)).saveIfAbsent(any());
        }
    }

    @Nested
    @DisplayName("內容在建立當下算好")
    class ContentIsSnapshotted {

        @Test
        @DisplayName("付款通知帶金額，且加了千分位與幣別")
        void paidNotificationFormatsAmount() {
            service.onOrderPaid(paidEvent());

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository, times(2)).saveIfAbsent(captor.capture());

            Notification first = captor.getAllValues().getFirst();
            assertThat(first.title()).isEqualTo("付款成功");
            // 「已收到 101700」會被誤讀，這是實際會造成客訴的細節
            assertThat(first.body()).contains("NT$ 101,700").contains(ORDER_NO);
        }

        @Test
        @DisplayName("出貨通知講使用者接下來能做什麼，不講系統改了什麼狀態")
        void shippedNotificationIsUserFacing() {
            service.onOrderShipped(new OrderShippedEvent("evt-ship-1", 1, ORDER_NO, USER, NOW));

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository, times(2)).saveIfAbsent(captor.capture());

            Notification first = captor.getAllValues().getFirst();
            assertThat(first.type()).isEqualTo(NotificationType.ORDER_SHIPPED);
            assertThat(first.body()).contains("配送進度");
            // 「狀態已更新為 SHIPPED」對使用者毫無資訊
            assertThat(first.body()).doesNotContain("SHIPPED");
        }

        @Test
        @DisplayName("內容裡不放連結——網址在不同環境不一樣，寫進快照會寄出指向 localhost 的信")
        void contentCarriesNoUrls() {
            service.onOrderPaid(paidEvent());
            service.onOrderShipped(new OrderShippedEvent("evt-ship-2", 1, ORDER_NO, USER, NOW));

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository, times(4)).saveIfAbsent(captor.capture());

            assertThat(captor.getAllValues())
                    .allSatisfy(notification ->
                            assertThat(notification.body()).doesNotContain("http"));
        }
    }

    @Nested
    @DisplayName("過期的事件不通知")
    class StaleEvents {

        @Test
        @DisplayName("重播的歷史事件一律略過——否則第一次部署會把每個人的整段歷史都通知一遍")
        void skipsReplayedHistory() {
            // 實機踩過：新的消費組加上 auto-offset-reset=earliest，
            // 1.6 秒內產生 80 筆通知，內容是幾個月前就已經送達的訂單
            OrderPaidEvent old = new OrderPaidEvent("evt-old", 1, ORDER_NO, USER,
                    new BigDecimal("100"), NOW.minus(Duration.ofDays(30)));

            service.onOrderPaid(old);

            verify(notificationRepository, never()).saveIfAbsent(any());
        }

        @Test
        @DisplayName("只是延遲幾小時的事件照樣通知——那才是真正該送到的")
        void stillNotifiesDelayedEvents() {
            // Kafka 積壓、消費端維護、DLQ 重投都會造成幾小時的延遲，
            // 窗口太窄會把這些一起濾掉，而那才是會被客訴的漏通知
            OrderPaidEvent delayed = new OrderPaidEvent("evt-delayed", 1, ORDER_NO, USER,
                    new BigDecimal("100"), NOW.minus(Duration.ofHours(6)));

            service.onOrderPaid(delayed);

            verify(notificationRepository, times(2)).saveIfAbsent(any());
        }

        @Test
        @DisplayName("occurredAt 為 null 時照樣通知——預設成略過會讓漏通知沒有症狀可循")
        void missingTimestampDoesNotSuppress() {
            OrderPaidEvent noTimestamp = new OrderPaidEvent("evt-null", 1, ORDER_NO, USER,
                    new BigDecimal("100"), null);

            service.onOrderPaid(noTimestamp);

            verify(notificationRepository, times(2)).saveIfAbsent(any());
        }
    }

    @Nested
    @DisplayName("並非每個事件都通知")
    class NotEveryEvent {

        @Test
        @DisplayName("通知類型只涵蓋會改變使用者預期的里程碑")
        void onlyUserFacingMilestones() {
            // 系統有八個領域事件，這裡只該有五個通知類型。
            // order.created 在尖峰時每秒上萬筆、而且使用者正盯著畫面；
            // payment.succeeded 從買家角度與 order.paid 是同一件事；
            // payment.refund-required 是內部競態補償，買家看不懂。
            //
            // 這條在「順手為新事件加一個通知」時會失敗，而那正是要的：
            // 加之前先想清楚使用者是否需要因為它做什麼
            assertThat(NotificationType.values())
                    .containsExactlyInAnyOrder(
                            NotificationType.ORDER_PAID,
                            NotificationType.ORDER_SHIPPED,
                            NotificationType.ORDER_COMPLETED,
                            NotificationType.ORDER_CANCELLED,
                            NotificationType.REFUND_SENT);
        }
    }
}
