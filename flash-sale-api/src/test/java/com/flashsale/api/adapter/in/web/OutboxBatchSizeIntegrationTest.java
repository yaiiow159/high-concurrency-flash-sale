package com.flashsale.api.adapter.in.web;

import com.flashsale.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.OutboxEventJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Outbox 的批次上限。 */
// 整合測試就是 dev：預設金鑰只在這個 profile 下放行（SecretGuard）
@ActiveProfiles("dev")
@SpringBootTest
@Testcontainers
@DisplayName("Outbox 批次上限")
class OutboxBatchSizeIntegrationTest {

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
        // 中繼排程必須停掉，否則它會在測試斷言之前把事件搬走
        registry.add("flash-sale.outbox.relay-interval-ms", () -> "3600000");
        registry.add("flash-sale.order.close-interval-ms", () -> "3600000");
        registry.add("flash-sale.stock.warmup-interval-ms", () -> "3600000");
        registry.add("flash-sale.reconciliation.interval-ms", () -> "3600000");
        registry.add("flash-sale.reconciliation.initial-delay-ms", () -> "3600000");
        registry.add("flash-sale.payment.refund-scan-interval-ms", () -> "3600000");
        registry.add("flash-sale.notification.delivery-interval-ms", () -> "3600000");
    }

    private static final int LIMIT = 200;
    private static final int TOTAL = 500;

    @Autowired private OutboxEventJpaRepository outboxRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("findPending 必須真的只回傳上限筆數——否則批次設定形同虛設")
    void findPendingRespectsLimit() {
        transactionTemplate.executeWithoutResult(status ->
                outboxRepository.saveAll(IntStream.range(0, TOTAL)
                        .mapToObj(n -> new OutboxEventEntity(
                                "limit-probe-" + n, "order.created", "AGG-" + n, "{}",
                                Instant.parse("2026-09-04T10:00:00Z")))
                        .toList()));

        List<OutboxEventEntity> batch = outboxRepository.findPending(Limit.of(LIMIT));

        assertThat(batch)
                .as("待投遞有 %d 筆，findPending(%d) 卻回了 %d 筆——"
                        + "上限沒生效的話，積壓時整張表會被載進單一交易",
                        TOTAL, LIMIT, batch.size())
                .hasSize(LIMIT);
    }
}
