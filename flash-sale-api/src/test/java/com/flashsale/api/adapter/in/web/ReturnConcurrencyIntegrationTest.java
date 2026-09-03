package com.flashsale.api.adapter.in.web;

import com.flashsale.application.port.in.ReturnUseCase;
import com.flashsale.application.port.in.command.OpenReturnCommand;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.ReturnRequestRepository;
import com.flashsale.domain.aftersales.ReturnLine;
import com.flashsale.domain.aftersales.ReturnReason;
import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.ShippingInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退貨額度的併發正確性——對著<b>真實的 MySQL</b> 執行。
 *
 * <h2>為什麼非得用真的資料庫</h2>
 *
 * <p>「可退數量」的檢查是
 * <b>讀既有退貨單 → 比對餘額 → 寫入新退貨單</b> 這個 read-modify-write。
 * 它的正確性完全取決於資料庫的隔離與鎖，而那正是 mock 結構上看不到的東西：
 * 用 mock 寫的循序測試（{@code ReturnServiceTest} 那幾條）永遠會通過，
 * 因為 mock 不會讓兩個交易交錯。
 *
 * <p>這個漏洞實際存在過並被實機重現：一張只買了 2 件的訂單，
 * 兩個併發請求各申請退 2 件，<b>兩張都成立</b>，累計申請 4 件。
 * 資料庫層擋不住——一張訂單本來就能有多張退貨單，
 * 所以 {@code return_request} 上沒有、也不該有 {@code order_no} 的唯一鍵。
 *
 * <p>修法是在 {@code open()} 一開始對訂單列取悲觀鎖，把同一張訂單的
 * 額度計算序列化。這與 ADR-0003「不要用鎖包住庫存扣減」不衝突：
 * 那條講的是所有請求搶同一行的秒殺熱路徑；退貨是冷路徑，
 * 而且臨界區裡全是資料庫操作，沒有遠端呼叫會把鎖撐住。
 */
@SpringBootTest
@Testcontainers
@DisplayName("退貨額度併發")
class ReturnConcurrencyIntegrationTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("flash_sale")
                    .withUsername("flashsale")
                    .withPassword("flashsale");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("flash-sale.outbox.relay-interval-ms", () -> "3600000");
        registry.add("flash-sale.order.close-interval-ms", () -> "3600000");
        registry.add("flash-sale.stock.warmup-interval-ms", () -> "3600000");
        registry.add("flash-sale.reconciliation.interval-ms", () -> "3600000");
        registry.add("flash-sale.reconciliation.initial-delay-ms", () -> "3600000");
        registry.add("flash-sale.payment.refund-scan-interval-ms", () -> "3600000");
    }

    private static final long USER_ID = 4242L;
    private static final long SKU_ID = 9001L;
    private static final int ORDERED_QUANTITY = 2;

    @Autowired private ReturnUseCase returnUseCase;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ReturnRequestRepository returnRepository;
    /** 建立訂單要在交易裡：{@code saveIfAbsent} 會一併寫 outbox，而那是 MANDATORY 傳播。 */
    @Autowired private TransactionTemplate transactionTemplate;

    /** 已付款、未出貨的訂單：只買了 {@value #ORDERED_QUANTITY} 件。 */
    private Order paidOrder() {
        OrderNo orderNo = OrderNo.of(String.valueOf(System.nanoTime()));
        Instant now = Instant.parse("2026-09-03T10:00:00Z");
        Order order = Order.place(orderNo, USER_ID, UUID.randomUUID().toString(),
                List.of(new OrderLine(SKU_ID, "併發測試商品",
                        new BigDecimal("100"), ORDERED_QUANTITY, null)),
                new ShippingInfo("收件人", "0912345678", "100", "台北市", "中正區", "測試路一段"),
                now);
        order.pay(now);
        order.pullDomainEvents();
        return transactionTemplate.execute(
                status -> orderRepository.saveIfAbsent(order).orElseThrow());
    }

    @Test
    @DisplayName("同時申請退同一批商品，總申請量不可超過訂購量——否則同一件貨會被退兩次")
    void concurrentRequestsCannotExceedOrderedQuantity() throws Exception {
        Order order = paidOrder();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    // 所有執行緒卡在同一道閘門上，確保真的同時進入臨界區。
                    // 少了這一步，執行緒會因為啟動時間差而自然錯開，
                    // 測試就變成「循序跑八次」，永遠不會失敗
                    fire.await();
                    returnUseCase.open(new OpenReturnCommand(
                            order.orderNo().value(), USER_ID, UUID.randomUUID().toString(),
                            List.of(new OpenReturnCommand.Item(SKU_ID, ORDERED_QUANTITY)),
                            ReturnReason.CHANGED_MIND, null));
                    accepted.incrementAndGet();
                } catch (Exception expected) {
                    // 被額度或鎖擋下是正確結果
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        fire.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        List<ReturnRequest> created = returnRepository.findByOrderNo(order.orderNo().value());
        int claimed = created.stream()
                .flatMap(request -> request.lines().stream())
                .filter(line -> line.skuId().equals(SKU_ID))
                .mapToInt(ReturnLine::quantity)
                .sum();

        assertThat(claimed)
                .as("訂購 %d 件，累計申請退貨 %d 件——超過就是同一件貨被退兩次",
                        ORDERED_QUANTITY, claimed)
                .isEqualTo(ORDERED_QUANTITY);
        assertThat(accepted.get())
                .as("每張單都申請滿額，因此只能有一張成立")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("同一個 requestId 重送只會產生一張退貨單——逾時重試不該變成退兩次")
    void sameRequestIdIsIdempotent() {
        Order order = paidOrder();
        String requestId = UUID.randomUUID().toString();

        OpenReturnCommand command = new OpenReturnCommand(
                order.orderNo().value(), USER_ID, requestId,
                List.of(new OpenReturnCommand.Item(SKU_ID, 1)),
                ReturnReason.CHANGED_MIND, null);

        String first = returnUseCase.open(command).returnNo();
        String second = returnUseCase.open(command).returnNo();

        assertThat(second).isEqualTo(first);
        assertThat(returnRepository.findByOrderNo(order.orderNo().value())).hasSize(1);
    }
}
