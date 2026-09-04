package com.flashsale.api.adapter.in.web;

import com.flashsale.application.port.out.PromotionRepository;
import com.flashsale.domain.promotion.CouponStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一張券只能用一次——對著<b>真實的 MySQL</b> 執行。
 *
 * <h2>為什麼非得用真的資料庫</h2>
 *
 * <p>券的核銷寫成一句條件式 UPDATE（{@code WHERE status = 'ISSUED'}），
 * 它的正確性完全來自資料庫在同一個語句內完成檢查與寫入。
 * mock 測不到這件事：mock 的 {@code redeem} 回什麼就是什麼，
 * 換成「先 SELECT 再 UPDATE」的錯誤實作，mock 測試照樣全綠。
 *
 * <p>這與退貨額度那次（{@code ReturnConcurrencyIntegrationTest}）是同一類
 * read-modify-write，但解法刻意不同：退貨的臨界區跨多個語句、只能用悲觀鎖；
 * 券可以壓成一句，就不該加鎖。<b>能原子化的東西不要用鎖保護</b>——
 * 鎖是多一個會失敗的東西。
 */
@SpringBootTest
@Testcontainers
@DisplayName("優惠券核銷併發")
class CouponConcurrencyIntegrationTest {

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

    private static final long USER_ID = 5252L;

    @Autowired private PromotionRepository promotionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    /** {@code redeem} 是 MANDATORY 傳播——沒有外層交易會直接拋例外，那也是它該有的行為。 */
    @Autowired private TransactionTemplate transactionTemplate;

    /**
     * 發一張 {@code expiresInSeconds} 秒後到期的券，回傳 ID。
     * 規則沿用 V15 種下的那筆示範優惠。
     *
     * <p><b>到期時間用 {@code UTC_TIMESTAMP()} 由資料庫自己算，不從 Java 傳。</b>
     * Spring Boot 3 預設 {@code hibernate.timezone.default_storage=NORMALIZE_UTC}，
     * 應用程式寫進 DATETIME 欄位的是 UTC；而 {@code Timestamp.from(instant)}
     * 經由裸 JDBC 走的是 JVM 預設時區。兩者差一個時區偏移，
     * 而那個差距足以讓「已過期」的券看起來還有八小時可用——
     * 第一版的這個測試就是這樣誤報成產品缺陷的。
     */
    private long issueCoupon(int expiresInSeconds) {
        Long promotionId = jdbcTemplate.queryForObject(
                "select id from promotion order by id limit 1", Long.class);
        String code = "TEST-" + System.nanoTime();
        jdbcTemplate.update(
                "insert into coupon (user_id, promotion_id, code, status, expires_at) "
                        + "values (?, ?, ?, 'ISSUED', "
                        + "date_add(utc_timestamp(3), interval ? second))",
                USER_ID, promotionId, code, expiresInSeconds);
        return jdbcTemplate.queryForObject(
                "select id from coupon where code = ?", Long.class, code);
    }

    private long issueCoupon() {
        return issueCoupon(86400);
    }

    @Test
    @DisplayName("八個執行緒同時核銷同一張券，只有一個能成功")
    void onlyOneThreadCanRedeemACoupon() throws Exception {
        long couponId = issueCoupon();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger redeemed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            String orderNo = "ORDER-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    // 所有執行緒卡在同一道閘門上，確保真的同時進入臨界區。
                    // 少了這一步，執行緒會因啟動時間差而自然錯開，
                    // 測試就變成「循序跑八次」，永遠不會失敗
                    fire.await();
                    boolean ok = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                            promotionRepository.redeem(couponId, orderNo, Instant.now())));
                    if (ok) {
                        redeemed.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 死鎖或逾時被擋下也是「沒核銷成功」，與回 false 等價
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        fire.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(redeemed.get())
                .as("八個並行請求核銷同一張券，成功次數必須恰好是 1——"
                        + "大於 1 代表同一張券折了兩次錢")
                .isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "select status from coupon where id = ?", String.class, couponId);
        assertThat(status).isEqualTo(CouponStatus.USED.name());

        // 誰核銷成功不重要，重要的是只有一張訂單掛在這張券上
        Integer withOrder = jdbcTemplate.queryForObject(
                "select count(*) from coupon where id = ? and used_order_no is not null",
                Integer.class, couponId);
        assertThat(withOrder).isEqualTo(1);
    }

    @Test
    @DisplayName("過期的券核銷不了——狀態還是 ISSUED 但已過期，只看狀態會放它過")
    void expiredCouponCannotBeRedeemed() {
        // 把券標成 EXPIRED 的批次任務不會即時跑，所以「狀態是 ISSUED 但已過期」
        // 是常態而非異常。條件式 UPDATE 必須自己把時間也算進去
        long couponId = issueCoupon(-60);

        boolean ok = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                promotionRepository.redeem(couponId, "ORDER-X", Instant.now())));

        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("核銷必須在交易內——沒有外層交易時直接拒絕，不安靜地自己開一個")
    void redeemOutsideTransactionIsRejected() {
        long couponId = issueCoupon();

        // MANDATORY 而非 REQUIRED：券的核銷與訂單建立必須同生共死（ADR-0013 決策 7）。
        // 用 REQUIRED 的話，有人在交易外呼叫時它會安靜地自己開一個交易，
        // 而那正是「建單失敗但券已消失」的來源
        assertThatThrownBy(() -> promotionRepository.redeem(couponId, "ORDER-Y", Instant.now()))
                .isNotNull();

        String status = jdbcTemplate.queryForObject(
                "select status from coupon where id = ?", String.class, couponId);
        assertThat(status).isEqualTo(CouponStatus.ISSUED.name());
    }
}
