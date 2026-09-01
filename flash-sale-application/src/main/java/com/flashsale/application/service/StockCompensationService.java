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

/**
 * 庫存補償服務——Saga 補償鏈的末端執行者。
 *
 * <p><b>為什麼補償要獨立成一個非同步步驟，而不是在關單的交易裡直接退 Redis？</b>
 * 因為 Redis 無法加入資料庫交易。若在交易內退庫而交易隨後回滾，庫存就會憑空多出來——
 * 這是比少賣更嚴重的超賣。
 *
 * <p>正確作法是「先在 DB 交易內把關單與退庫事件一起 commit，再由此服務消費事件退庫」。
 * 事件投遞是至少一次，因此退庫操作本身必須冪等——由 Lua 腳本以 {@code requestId} 保證。
 *
 * <p><b>多品項後改為逐筆退回。</b> 一張訂單可能佔用多個活動的庫存，
 * 單品項時代那個扁平的「一個 activityId + 一個 quantity」在多品項下會漏退——
 * 而漏退的那些庫存不會有任何錯誤訊息，只會靜靜地消失。
 */
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
