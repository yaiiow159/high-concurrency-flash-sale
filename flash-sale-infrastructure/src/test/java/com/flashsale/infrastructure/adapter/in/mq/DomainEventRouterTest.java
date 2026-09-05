package com.flashsale.infrastructure.adapter.in.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.domain.order.event.OrderPaidEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事件分派。
 *
 * <p>這段先前在六個消費端各寫一次，而<b>漏寫它的後果不是拋例外，
 * 是安靜地處理錯的事件</b>——Jackson 反序列化到不相符的類別時，
 * 缺少的欄位變成 null 而不是報錯。
 */
@DisplayName("事件分派")
class DomainEventRouterTest {

    private final DomainEventRouter router = new DomainEventRouter(new ObjectMapper()
            .findAndRegisterModules());

    private static String paidEventJson() {
        return """
                {"eventId":"e-1","schemaVersion":2,"orderNo":"20260906000001",
                 "userId":88,"totalAmount":1299.00,"occurredAt":"2026-09-06T00:00:00Z"}
                """;
    }

    @Test
    @DisplayName("型別相符時反序列化並交給處理器")
    void routesMatchingType() throws Exception {
        AtomicReference<OrderPaidEvent> seen = new AtomicReference<>();

        boolean handled = router.route(paidEventJson(), OrderPaidEvent.TYPE,
                OrderPaidEvent.TYPE, OrderPaidEvent.class, seen::set);

        assertThat(handled).isTrue();
        assertThat(seen.get().orderNo()).isEqualTo("20260906000001");
        assertThat(seen.get().totalAmount()).isEqualByComparingTo(new BigDecimal("1299.00"));
    }

    @Test
    @DisplayName("型別不符時完全不碰 payload——連反序列化都不做")
    void ignoresOtherTypes() throws Exception {
        AtomicReference<OrderPaidEvent> seen = new AtomicReference<>();

        // payload 故意給一段不合法的 JSON：如果 router 先反序列化再比對型別，
        // 這裡會爆炸。而尖峰時 order.created 每秒上萬筆，
        // 為它們付一次反序列化是純粹的浪費
        boolean handled = router.route("{{{ not json", "order.created",
                OrderPaidEvent.TYPE, OrderPaidEvent.class, seen::set);

        assertThat(handled).isFalse();
        assertThat(seen.get()).isNull();
    }

    @Test
    @DisplayName("事件型別標頭缺失時視為不相符，不可當成相符")
    void missingHeaderIsNotAMatch() throws Exception {
        // Kafka 標頭是 required = false，缺失時 eventType 是 null。
        // 若寫成 `eventType.equals(expected)` 會 NPE，
        // 若寫成寬鬆比對則會把來路不明的訊息當成自己的
        boolean handled = router.route(paidEventJson(), null,
                OrderPaidEvent.TYPE, OrderPaidEvent.class, event -> { });

        assertThat(handled).isFalse();
    }

    @Test
    @DisplayName("處理器拋出的例外原樣往外傳，不包成 RuntimeException")
    void propagatesHandlerException() {
        // Kafka 的 DefaultErrorHandler 是**按例外型別**比對不可重試清單的。
        // 包成 RuntimeException 會讓 IllegalStateException 這類
        // 「重試一萬次也一樣」的錯誤被當成可重試，白白拖住整個分區
        assertThatThrownBy(() -> router.route(paidEventJson(), OrderPaidEvent.TYPE,
                OrderPaidEvent.TYPE, OrderPaidEvent.class, event -> {
                    throw new IllegalStateException("處理失敗");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("處理失敗");
    }
}
