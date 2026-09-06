package com.flashsale.application.service;

import com.flashsale.application.port.in.StockCompensationUseCase;
import com.flashsale.application.port.out.SeckillRequestTracker;
import com.flashsale.application.port.out.SoldOutMarker;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.domain.order.event.OrderCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 庫存補償服務——Saga 補償鏈的末端執行者。 */
@Service
public class StockCompensationService implements StockCompensationUseCase {

    private static final Logger log = LoggerFactory.getLogger(StockCompensationService.class);

    private final StockRepository stockRepository;
    private final SoldOutMarker soldOutMarker;
    private final SeckillRequestTracker requestTracker;
    private final SeckillMetrics metrics;

    public StockCompensationService(StockRepository stockRepository,
                                    SoldOutMarker soldOutMarker,
                                    SeckillRequestTracker requestTracker,
                                    SeckillMetrics metrics) {
        this.stockRepository = stockRepository;
        this.soldOutMarker = soldOutMarker;
        this.requestTracker = requestTracker;
        this.metrics = metrics;
    }

    @Override
    public void compensate(OrderCancelledEvent event) {
        if (!event.hasStockToRestore()) {
            // 純一般下單的訂單沒有 Redis 庫存要退，它們走資料庫庫存的補償路徑
            log.debug("訂單 {} 沒有秒殺庫存需要退回", event.orderNo());
            return;
        }

        for (OrderCancelledEvent.StockRestoration restoration : event.restorations()) {
            boolean restored = restoreStock(restoration.activityId(), event.userId(),
                    restoration.quantity(), event.requestId(), "order-cancelled");
            if (restored) {
                log.info("訂單關閉已退回庫存 orderNo={}, 活動={}, 數量={}, 原因={}",
                        event.orderNo(), restoration.activityId(), restoration.quantity(), event.reason());
            }
        }
    }

    @Override
    public void compensateDeadLetter(SeckillOrderMessage message, String reason) {
        log.warn("建單訊息進入死信佇列，開始退庫 orderNo={}, requestId={}, 原因={}",
                message.orderNo(), message.requestId(), reason);

        restoreStock(message.activityId(), message.userId(), message.quantity(),
                message.requestId(), "dead-letter");

        // 讓仍在輪詢的前端能立刻停下，而不是一路等到逾時。
        requestTracker.markFailed(message.orderNo(), "訂單建立失敗，庫存已退回");
    }

    private boolean restoreStock(Long activityId, Long userId, int quantity,
                                 String requestId, String trigger) {
        try {
            boolean restored = stockRepository.restore(activityId, userId, quantity, requestId);
            if (restored) {
                // 有庫存回補，撤下售罄標記讓退回的量能被重新搶購。
                soldOutMarker.clear(activityId);
            } else {
                log.debug("requestId={} 無需退庫（未曾扣減或已退過）", requestId);
            }
            metrics.recordCompensation(activityId, trigger, true);
            return restored;
        } catch (RuntimeException e) {
            metrics.recordCompensation(activityId, trigger, false);
            // 往上拋讓 MQ 重試；重試耗盡後會留在 DLQ 供人工處理，絕不可靜默吞掉。
            throw e;
        }
    }
}
