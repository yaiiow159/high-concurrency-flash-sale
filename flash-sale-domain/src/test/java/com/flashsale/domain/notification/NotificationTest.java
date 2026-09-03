package com.flashsale.domain.notification;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("通知")
class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(600);
    private static final long USER = 77L;

    private static Notification of(NotificationChannel channel) {
        return Notification.compose(USER, channel, NotificationType.ORDER_SHIPPED,
                "商品已出貨", "訂單 123 已交給物流。", "123", "evt-1", NOW);
    }

    @Nested
    @DisplayName("兩個管道的生命週期不同")
    class ChannelLifecycle {

        @Test
        @DisplayName("站內信建立即為已送出——它不經過任何外部系統")
        void inAppIsSentOnCreation() {
            Notification notification = of(NotificationChannel.IN_APP);

            assertThat(notification.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(notification.sentAt()).isEqualTo(NOW);
            assertThat(notification.isUnread()).isTrue();
        }

        @Test
        @DisplayName("Email 建立時是待寄送，要等排程真的寄出")
        void emailStartsPending() {
            Notification notification = of(NotificationChannel.EMAIL);

            assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
            assertThat(notification.sentAt()).isNull();
            // Email 沒有未讀概念：我們無從得知使用者有沒有讀
            assertThat(notification.isUnread()).isFalse();
        }

        @Test
        @DisplayName("Email 不能標記已讀——我們無從得知它有沒有被讀")
        void emailHasNoReadState() {
            Notification notification = of(NotificationChannel.EMAIL);

            assertThatThrownBy(() -> notification.markRead(LATER))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
        }
    }

    @Nested
    @DisplayName("寄送")
    class Delivery {

        @Test
        @DisplayName("寄出時記下實際寄達的地址——那是寄送紀錄，不是使用者現在的信箱")
        void recordsActualRecipient() {
            Notification notification = of(NotificationChannel.EMAIL);

            notification.markSent("someone@example.com", LATER);

            assertThat(notification.recipient()).isEqualTo("someone@example.com");
            assertThat(notification.sentAt()).isEqualTo(LATER);
            assertThat(notification.attemptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("暫時性失敗仍可重試，且重試成功會清掉失敗原因")
        void transientFailureCanRecover() {
            Notification notification = of(NotificationChannel.EMAIL);

            notification.markFailed("SMTP 逾時", LATER);
            assertThat(notification.status().awaitingDelivery()).isTrue();

            assertThatCode(() -> notification.markSent("a@b.com", LATER))
                    .doesNotThrowAnyException();
            assertThat(notification.failureReason()).isNull();
            assertThat(notification.attemptCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("永久性失敗不再被排程撈取，但原因留著供查")
        void permanentFailureStopsRetrying() {
            Notification notification = of(NotificationChannel.EMAIL);

            notification.markUndeliverable("信箱格式不合法", LATER);

            assertThat(notification.status()).isEqualTo(NotificationStatus.UNDELIVERABLE);
            // 這是關鍵：混在 FAILED 裡的話排程每一輪都會白撈一次
            assertThat(notification.status().awaitingDelivery()).isFalse();
            assertThat(notification.failureReason()).isEqualTo("信箱格式不合法");
        }

        @Test
        @DisplayName("已寄出就是終態——重複投遞不該把同一封信再寄一次")
        void sentIsTerminal() {
            Notification notification = of(NotificationChannel.EMAIL);
            notification.markSent("a@b.com", LATER);

            assertThatThrownBy(() -> notification.markSent("a@b.com", LATER))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> notification.markFailed("x", LATER))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("已讀")
    class Read {

        @Test
        @DisplayName("重複標記已讀不視為錯誤，也不會覆寫第一次的時間")
        void markingReadTwiceIsHarmless() {
            Notification notification = of(NotificationChannel.IN_APP);

            notification.markRead(LATER);
            notification.markRead(LATER.plusSeconds(60));

            // 兩個分頁同時開著同一封通知是正常操作，
            // 為此拋例外只會讓畫面出現使用者看不懂的錯誤
            assertThat(notification.readAt()).isEqualTo(LATER);
            assertThat(notification.isUnread()).isFalse();
        }
    }

    @Nested
    @DisplayName("內容是快照")
    class ContentSnapshot {

        @Test
        @DisplayName("標題與內容沒有 setter——已經寄出去的信不能被事後改寫")
        void contentHasNoMutator() {
            // 這條靠反射檢查而不是靠人眼：加一個 setTitle 是很自然的「順手」改動，
            // 而它的後果是「我們對使用者說過什麼」變得可以事後改寫
            assertThat(Notification.class.getDeclaredMethods())
                    .noneMatch(method -> method.getName().equals("setTitle")
                            || method.getName().equals("setBody"));
        }

        @Test
        @DisplayName("空標題或空內容一律拒絕——寄一封沒有內容的信比不寄更糟")
        void rejectsEmptyContent() {
            assertThatThrownBy(() -> Notification.compose(USER, NotificationChannel.EMAIL,
                    NotificationType.ORDER_PAID, "  ", "內容", "123", "evt", NOW))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> Notification.compose(USER, NotificationChannel.EMAIL,
                    NotificationType.ORDER_PAID, "標題", "", "123", "evt", NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("來源事件 ID 不可為空——那是唯一的冪等依據")
        void requiresSourceEventId() {
            assertThatThrownBy(() -> Notification.compose(USER, NotificationChannel.EMAIL,
                    NotificationType.ORDER_PAID, "標題", "內容", "123", null, NOW))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("擁有者")
    class Ownership {

        @Test
        @DisplayName("只認自己的使用者")
        void belongsToOwnerOnly() {
            Notification notification = of(NotificationChannel.IN_APP);

            assertThat(notification.belongsTo(USER)).isTrue();
            assertThat(notification.belongsTo(USER + 1)).isFalse();
        }
    }
}
