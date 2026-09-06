package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.out.DistributedLock;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 寄送待發的 Email 通知。 */
@Component
public class NotificationDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryScheduler.class);

    /** 暫時性失敗的重試上限。 */
    private static final int MAX_ATTEMPTS = 5;

    private static final int BATCH_SIZE = 50;

    private static final String LOCK_KEY = "seckill:lock:notification-delivery";

    /** 租期上限。 */
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);

    private final NotificationRepository notificationRepository;
    private final MailSender mailSender;
    private final Deliverer deliverer;
    private final DistributedLock distributedLock;

    public NotificationDeliveryScheduler(NotificationRepository notificationRepository,
                                         MailSender mailSender,
                                         Deliverer deliverer,
                                         DistributedLock distributedLock) {
        this.notificationRepository = notificationRepository;
        this.mailSender = mailSender;
        this.deliverer = deliverer;
        this.distributedLock = distributedLock;
    }

    @Scheduled(fixedDelayString = "${flash-sale.notification.delivery-interval-ms:30000}")
    public void deliverPending() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    /** 吞掉例外：排程拋出未捕捉例外會被 Spring 取消後續排程， 而通知靜默停擺不會有任何告警。與其他排程一致。 */
    private void runSafely() {
        try {
            deliverBatch();
        } catch (RuntimeException e) {
            log.error("通知寄送排程執行失敗，本輪略過", e);
        }
    }

    private void deliverBatch() {
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

    /** 寄送單筆的交易邊界。 */
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

        /** 寄一封信。 */
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
