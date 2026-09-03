package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.out.MailSender;
import com.flashsale.application.port.out.NotificationRepository;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.notification.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 寄送待發的 Email 通知。
 *
 * <p>與 {@code PaymentRefundScheduler} 同一個形狀：MQ 消費端只把通知寫成
 * {@code PENDING}，真正的遠端呼叫留給排程。這樣消費端不會因為某個人的
 * 信箱掛掉而拖住整個分區。
 *
 * <p><b>單筆失敗不中斷整批。</b>一個信箱寄不出去，不該讓其他人的通知也卡著。
 */
@Component
public class NotificationDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryScheduler.class);

    /**
     * 暫時性失敗的重試上限。
     *
     * <p>超過就不再撈取，但紀錄與失敗原因都留著——
     * 刪掉會讓「為什麼這個人沒收到信」變成無解的問題。
     *
     * <p>永久性失敗（信箱不存在、使用者已刪除）不受這個數字管：
     * 它們直接轉入 {@code UNDELIVERABLE} 終態，第一次就不再被撈取。
     * 混在一起的話排程每一輪都會白撈那些永遠寄不出去的。
     */
    private static final int MAX_ATTEMPTS = 5;

    private static final int BATCH_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final MailSender mailSender;
    private final Deliverer deliverer;

    public NotificationDeliveryScheduler(NotificationRepository notificationRepository,
                                         MailSender mailSender,
                                         Deliverer deliverer) {
        this.notificationRepository = notificationRepository;
        this.mailSender = mailSender;
        this.deliverer = deliverer;
    }

    @Scheduled(fixedDelayString = "${flash-sale.notification.delivery-interval-ms:30000}")
    public void deliverPending() {
        List<Notification> pending = notificationRepository.findAwaitingDelivery(
                NotificationChannel.EMAIL, MAX_ATTEMPTS, BATCH_SIZE);
        if (pending.isEmpty()) {
            return;
        }

        int sent = 0;
        for (Notification notification : pending) {
            // 每一筆各自一個交易。整批共用一個的話，第 50 筆失敗會把
            // 前面 49 筆已經寄出去的信也標記回未寄——而那些信已經寄出去了
            if (deliverer.deliverOne(notification, mailSender)) {
                sent++;
            }
        }
        log.info("通知寄送完成：撈取 {} 筆，成功 {} 筆", pending.size(), sent);
    }

    /**
     * 寄送單筆的交易邊界。
     *
     * <p><b>拆成獨立 Bean 是為了讓 {@code @Transactional} 真的生效。</b>
     * Spring 的交易是動態代理，同一個 Bean 內部呼叫 {@code this.deliverOne()}
     * 不會經過代理，註解會安靜失效且沒有任何錯誤訊息。
     * 與 {@code OutboxRelayScheduler} / {@code OutboxRelayer} 的拆分同理。
     */
    @Component
    public static class Deliverer {

        private static final Logger log = LoggerFactory.getLogger(Deliverer.class);

        private final NotificationRepository notificationRepository;
        private final UserRepository userRepository;
        private final Clock clock;

        public Deliverer(NotificationRepository notificationRepository,
                         UserRepository userRepository,
                         Clock clock) {
            this.notificationRepository = notificationRepository;
            this.userRepository = userRepository;
            this.clock = clock;
        }

        /**
         * 寄一封信。
         *
         * <p><b>收件地址在這一刻才取</b>，不是在建立通知時就固定——
         * 使用者在排隊期間改了信箱，該寄到新的那個。
         * 但寄出之後 {@code recipient} 就固定成實際寄達的地址，
         * 那是寄送紀錄而不是「這個人現在的信箱」。
         *
         * <p>REQUIRES_NEW 而非 REQUIRED：呼叫端是迴圈，
         * 若沿用外層交易，任何一筆失敗都會把整批標成 rollback-only。
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public boolean deliverOne(Notification notification, MailSender mailSender) {
            Optional<User> user = userRepository.findById(notification.userId());
            if (user.isEmpty()) {
                // 使用者不存在是永久性失敗，重試沒有意義
                notification.markUndeliverable("使用者不存在", clock.instant());
                notificationRepository.update(notification);
                return false;
            }

            String recipient = user.get().email().value();
            try {
                MailSender.Outcome outcome = mailSender.send(
                        recipient, notification.title(), notification.body());
                if (outcome.succeeded()) {
                    notification.markSent(recipient, clock.instant());
                    notificationRepository.update(notification);
                    return true;
                }

                if (outcome.retryable()) {
                    notification.markFailed(outcome.failureReason(), clock.instant());
                    log.warn("通知寄送失敗，將於下一輪重試 userId={}, 原因={}",
                            notification.userId(), outcome.failureReason());
                } else {
                    // 轉入終態，排程不再撈取它。紀錄與原因都留著——
                    // 「為什麼這個人沒收到信」要查得到
                    notification.markUndeliverable(outcome.failureReason(), clock.instant());
                    log.error("通知寄送永久失敗，不再重試 userId={}, 原因={}",
                            notification.userId(), outcome.failureReason());
                }
                notificationRepository.update(notification);
                return false;
            } catch (RuntimeException e) {
                notification.markFailed(e.getClass().getSimpleName(), clock.instant());
                notificationRepository.update(notification);
                log.error("通知寄送發生例外 userId={}", notification.userId(), e);
                return false;
            }
        }
    }
}
