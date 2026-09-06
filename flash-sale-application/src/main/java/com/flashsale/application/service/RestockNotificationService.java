package com.flashsale.application.service;

import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.in.RestockNotificationUseCase;
import com.flashsale.application.port.out.NotificationRepository;
import com.flashsale.application.port.out.RestockSubscriptionRepository;
import com.flashsale.domain.notification.Notification;
import com.flashsale.domain.notification.NotificationChannel;
import com.flashsale.domain.notification.NotificationType;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 到貨通知。 */
@Service
public class RestockNotificationService implements RestockNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(RestockNotificationService.class);

    /**
     * 一個人最多同時等幾個 SKU。
     *
     * <p>沒有上限的話，一個腳本可以把整個目錄都訂閱起來，
     * 之後任何一次補貨都會把他變成一場小型信件風暴的來源。
     */
    private static final int MAX_PENDING_PER_USER = 50;

    /**
     * 一次補貨最多通知幾個人。
     *
     * <p>剩下的留到下一次補貨或由人工處理——**寧可少通知，不可一次送出幾萬封**。
     * 通知本身是寫進 {@code notification} 表，由寄送排程每 30 秒一批 50 封送出，
     * 那一層已經在削峰；這裡的上限是防止那張表被一次灌爆。
     */
    private static final int MAX_NOTIFY_PER_RESTOCK = 500;

    /** 一輪掃描最多處理幾個 SKU。沒處理完的下一輪還在。 */
    private static final int MAX_SKUS_PER_SCAN = 100;

    private final RestockSubscriptionRepository subscriptionRepository;
    private final NotificationRepository notificationRepository;
    private final CatalogQueryUseCase catalogQuery;
    private final Clock clock;

    public RestockNotificationService(RestockSubscriptionRepository subscriptionRepository,
                                      NotificationRepository notificationRepository,
                                      CatalogQueryUseCase catalogQuery,
                                      Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.notificationRepository = notificationRepository;
        this.catalogQuery = catalogQuery;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void subscribe(Long userId, Long skuId) {
        if (catalogQuery.findSkus(List.of(skuId)).isEmpty()) {
            throw new BusinessException(ErrorCode.SKU_NOT_FOUND);
        }
        if (subscriptionRepository.countPending(userId) >= MAX_PENDING_PER_USER) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "同時最多只能等 " + MAX_PENDING_PER_USER + " 個商品到貨");
        }
        subscriptionRepository.subscribe(userId, skuId, clock.instant());
    }

    @Override
    @Transactional
    public void unsubscribe(Long userId, Long skuId) {
        subscriptionRepository.unsubscribe(userId, skuId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> pendingSkuIds(Long userId) {
        return userId == null ? List.of() : subscriptionRepository.findPendingSkuIds(userId);
    }

    /**
     * 掃描補貨。
     *
     * <p>用輪詢而不是讓庫存服務回呼有兩個理由：補貨有好幾條路徑
     * （退貨、逾時關單、活動釋放、人工補貨），逐條掛勾一定會漏掉某一條；
     * 而且回呼會讓庫存反過來依賴通知，方向是錯的。
     *
     * <p>代價是最多晚一個掃描週期。對「到貨通知」來說那完全無所謂。
     */
    @Override
    @Transactional
    public int notifyAllRestocked() {
        int total = 0;
        for (Long skuId : subscriptionRepository.findRestockedSkuIds(MAX_SKUS_PER_SCAN)) {
            total += notifyWaiters(skuId);
        }
        return total;
    }

    /**
     * 補貨了，通知等待的人。
     *
     * <p><b>先標記已通知，再寫通知。</b> 反過來的話，寫到一半失敗重跑時
     * 前面那些人會再收到一次——而重複的到貨通知比漏掉一次更讓人惱火。
     * 標記在前，最壞是有人沒收到，那可以由他重新訂閱救回來。
     */
    @Override
    @Transactional
    public int notifyWaiters(Long skuId) {
        List<RestockSubscriptionRepository.Pending> waiters =
                subscriptionRepository.findWaitersFor(skuId, MAX_NOTIFY_PER_RESTOCK);
        if (waiters.isEmpty()) {
            return 0;
        }

        Instant now = clock.instant();
        int marked = subscriptionRepository.markNotified(
                waiters.stream().map(RestockSubscriptionRepository.Pending::subscriptionId).toList(),
                now);
        if (marked == 0) {
            // 另一個節點搶先處理完了。這不是錯誤，是互斥生效
            return 0;
        }

        String productName = catalogQuery.findSkus(List.of(skuId)).stream()
                .findFirst()
                .map(CatalogQueryUseCase.SkuLookup::productName)
                .orElse("你關注的商品");

        List<Notification> notifications = new ArrayList<>(waiters.size());
        for (RestockSubscriptionRepository.Pending waiter : waiters) {
            notifications.add(Notification.compose(
                    waiter.userId(),
                    NotificationChannel.IN_APP,
                    NotificationType.RESTOCKED,
                    "你等的商品到貨了",
                    productName + " 補貨了，數量有限，先買先得。",
                    String.valueOf(skuId),
                    // 訂閱 id 當來源事件 id：同一筆訂閱只會產生一則通知，
                    // 而消費端的 saveIfAbsent 會擋掉重複
                    "restock-" + waiter.subscriptionId(),
                    now));
        }
        notifications.forEach(notificationRepository::saveIfAbsent);

        log.info("SKU {} 補貨，通知了 {} 個人", skuId, waiters.size());
        return waiters.size();
    }
}
