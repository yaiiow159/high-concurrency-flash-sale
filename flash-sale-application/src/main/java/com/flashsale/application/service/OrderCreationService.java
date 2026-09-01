package com.flashsale.application.service;

import com.flashsale.application.port.in.OrderCreationUseCase;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * 建單服務——削峰後的慢車道，由 MQ 消費端驅動。
 *
 * <p>與 {@link SeckillApplicationService} 的關鍵差異：這裡<b>有</b>資料庫交易，
 * 因為訂單落庫與 Outbox 事件必須原子一致（見 ADR-0004）。
 * 消費端的併發度受控（由分區數決定），DB 壓力因此可預測，
 * 不會像同步下單那樣被前端流量直接沖垮。
 *
 * <p><b>三層冪等</b>：
 * <ol>
 *   <li>Redis Lua 的 requestId 判重（擋掉使用者連點）</li>
 *   <li>此處的 {@code saveIfAbsent}（擋掉 MQ 重複投遞）</li>
 *   <li>資料庫 {@code request_id} 唯一索引（前兩層都失效時的最終防線）</li>
 * </ol>
 */
@Service
public class OrderCreationService implements OrderCreationUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrderCreationService.class);

    private final OrderRepository orderRepository;
    private final ActivityRepository activityRepository;
    private final EventOutbox eventOutbox;
    private final SeckillMetrics metrics;
    private final Clock clock;

    public OrderCreationService(OrderRepository orderRepository,
                                ActivityRepository activityRepository,
                                EventOutbox eventOutbox,
                                SeckillMetrics metrics,
                                Clock clock) {
        this.orderRepository = orderRepository;
        this.activityRepository = activityRepository;
        this.eventOutbox = eventOutbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean createFrom(SeckillOrderMessage message) {
        SeckillActivity activity = activityRepository.findById(message.activityId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND,
                        "建單時找不到活動 " + message.activityId()));

        Order order = Order.forSeckill(
                OrderNo.of(message.orderNo()),
                activity,
                message.userId(),
                message.requestId(),
                message.quantity(),
                clock.instant());

        Optional<Order> saved = orderRepository.saveIfAbsent(order);
        if (saved.isEmpty()) {
            // 重複投遞不是錯誤，是 at-least-once 的正常結果，直接 ack 掉即可。
            log.info("訂單已存在，略過重複訊息 requestId={}, orderNo={}",
                    message.requestId(), message.orderNo());
            metrics.recordOrderPersisted(message.activityId(), "duplicate");
            return false;
        }

        // 事件與訂單同交易寫入 Outbox：commit 成功即代表事件必定會被投遞。
        eventOutbox.append(order.pullDomainEvents());
        metrics.recordOrderPersisted(message.activityId(), "created");
        log.debug("訂單建立完成 orderNo={}", message.orderNo());
        return true;
    }
}
