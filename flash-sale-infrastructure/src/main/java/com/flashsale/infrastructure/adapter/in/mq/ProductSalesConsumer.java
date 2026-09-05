package com.flashsale.infrastructure.adapter.in.mq;

import com.flashsale.application.port.in.ProductSalesUseCase;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 銷量計入。
 *
 * <h2>「把歷史全部重跑一次會怎樣」</h2>
 *
 * <p>CLAUDE.md 鐵則 4 要求新增消費端時回答兩個問題，
 * 而這個消費端的第二題答案原本是<b>會出事</b>：
 * {@code auto-offset-reset} 是 earliest，新的 consumer group 第一次上線
 * 會重放整個 topic，而銷量走增量 UPDATE——重放等於把每一筆歷史訂單
 * 再加一次，銷量憑空翻倍且沒有任何錯誤訊息。
 *
 * <p>因此 {@code product_sales_applied} 那張表不是稽核用的，
 * 它是這個消費端能夠存在的前提。遷移回填歷史訂單時
 * 也一併寫進那張表，正是為了讓第一次重放安靜地跳過它們。
 *
 * <h2>時點取付款成功</h2>
 *
 * <p>下單只是意圖，收到錢才是成交。這也讓「未付款逾時關單」
 * 不必特別處理——它根本沒有被計入過。
 */
@Component
public class ProductSalesConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductSalesConsumer.class);

    private final ProductSalesUseCase productSalesUseCase;
    private final DomainEventRouter router;

    public ProductSalesConsumer(ProductSalesUseCase productSalesUseCase,
                                DomainEventRouter router) {
        this.productSalesUseCase = productSalesUseCase;
        this.router = router;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.product-sales-group:product-sales}",
            concurrency = "${flash-sale.mq.product-sales-concurrency:2}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        router.route(payload, eventType, OrderPaidEvent.TYPE, OrderPaidEvent.class, event -> {
            if (productSalesUseCase.recordSale(event.orderNo(), event.userId())) {
                log.debug("訂單 {} 已計入銷量", event.orderNo());
            }
        });
    }
}
