package com.flashsale.infrastructure.scheduler;

import com.flashsale.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.OutboxEventJpaRepository;
import com.flashsale.infrastructure.config.FlashSaleProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 中繼器。
 *
 * <h2>這支測試存在的理由</h2>
 *
 * <p>中繼器原本是逐筆 {@code send().get()}，註解寫著
 * 「批次非同步投遞無法精確知道哪幾筆成功」。那句話是錯的，
 * 而它讓中繼器付出了每筆一次序列往返的代價
 * （生產者設了 {@code linger.ms=5}，單執行緒逐筆送等於每筆固定 +5ms）。
 *
 * <p>改成「先全部送出、再一起收」之後，<b>唯一真正要證明的就是那句話確實是錯的</b>：
 * 一批裡有成功有失敗時，每一筆都要被正確地個別標記，
 * 不能整批標成功、也不能整批標失敗。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Outbox 中繼器")
class OutboxRelayerTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
    private static final int MAX_RETRY = 5;

    @Mock
    private OutboxEventJpaRepository outboxRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelayer relayer;

    @BeforeEach
    void setUp() {
        FlashSaleProperties properties = new FlashSaleProperties(
                new FlashSaleProperties.Mq(Duration.ofMillis(500)),
                null, null, null,
                new FlashSaleProperties.Outbox(200, MAX_RETRY, 7),
                null);
        relayer = new OutboxRelayer(outboxRepository, kafkaTemplate, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEventEntity event(String id) {
        return new OutboxEventEntity(id, "order.paid", "ORDER-" + id, "{}", NOW);
    }

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> rejected(String reason) {
        return CompletableFuture.failedFuture(new IllegalStateException(reason));
    }

    @Nested
    @DisplayName("逐筆狀態追蹤")
    class PerEventTracking {

        @Test
        @DisplayName("一批裡有成功有失敗時，每一筆都各自被正確標記")
        void mixedOutcomesAreTrackedIndividually() {
            OutboxEventEntity first = event("evt-1");
            OutboxEventEntity second = event("evt-2");
            OutboxEventEntity third = event("evt-3");
            when(outboxRepository.findPending(any(Limit.class)))
                    .thenReturn(List.of(first, second, third));
            when(kafkaTemplate.send(any(ProducerRecord.class)))
                    .thenReturn(acked())
                    .thenReturn(rejected("broker 拒絕"))
                    .thenReturn(acked());

            int published = relayer.relayPendingEvents();

            assertThat(published).isEqualTo(2);
            // 這三條就是「批次投遞無法知道哪幾筆成功」那句話的反證
            assertThat(first.getStatus()).isEqualTo(OutboxEventEntity.STATUS_PUBLISHED);
            assertThat(second.getStatus()).isEqualTo(OutboxEventEntity.STATUS_PENDING);
            assertThat(third.getStatus()).isEqualTo(OutboxEventEntity.STATUS_PUBLISHED);
            // 失敗的那筆才增加重試次數，成功的兩筆不受牽連
            assertThat(second.getRetryCount()).isEqualTo(1);
            assertThat(first.getRetryCount()).isZero();
            assertThat(third.getRetryCount()).isZero();
        }

        @Test
        @DisplayName("整批送完才開始等 ack——把 get 搬回迴圈裡就會失敗")
        void sendsAllBeforeAwaitingAnyAck() {
            List<OutboxEventEntity> batch = List.of(event("a"), event("b"), event("c"));
            when(outboxRepository.findPending(any(Limit.class))).thenReturn(batch);

            AtomicInteger sends = new AtomicInteger();
            AtomicInteger sendsWhenFirstAwaited = new AtomicInteger(-1);
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(call -> {
                sends.incrementAndGet();
                // 這個 future 在第一次被等待時，記下當下已經送出幾筆
                return new CompletableFuture<SendResult<String, String>>() {
                    @Override
                    public SendResult<String, String> get(long timeout, TimeUnit unit) {
                        sendsWhenFirstAwaited.compareAndSet(-1, sends.get());
                        return null;
                    }
                };
            });

            relayer.relayPendingEvents();

            // 逐筆 send().get() 的寫法在這裡會得到 1——那正是每筆各自獨佔一個
            // 生產者批次、linger.ms 從攤提成本變成每筆固定成本的原因
            assertThat(sendsWhenFirstAwaited.get())
                    .as("第一次等待 ack 時應該已經全部送出，否則就退回成序列往返")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("重試次數達上限後轉為 DEAD，讓告警抓得到")
        void exhaustedRetriesBecomeDead() {
            OutboxEventEntity stubborn = event("evt-dead");
            when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of(stubborn));
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(rejected("一直失敗"));

            for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
                relayer.relayPendingEvents();
            }

            assertThat(stubborn.getStatus()).isEqualTo(OutboxEventEntity.STATUS_DEAD);
            assertThat(stubborn.getRetryCount()).isEqualTo(MAX_RETRY);
        }
    }

    @Nested
    @DisplayName("同步失敗")
    class SynchronousFailure {

        @Test
        @DisplayName("send 本身就拋例外時照樣逐筆標記，不會拖垮整批")
        void sendThrowingIsHandledPerEvent() {
            OutboxEventEntity good = event("ok");
            OutboxEventEntity bad = event("boom");
            when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of(bad, good));
            // 序列化失敗或生產者緩衝區滿了超過 max.block.ms，都是同步拋出、沒有 future 可等
            when(kafkaTemplate.send(any(ProducerRecord.class)))
                    .thenThrow(new IllegalStateException("緩衝區已滿"))
                    .thenReturn(acked());

            int published = relayer.relayPendingEvents();

            assertThat(published).isEqualTo(1);
            assertThat(bad.getStatus()).isEqualTo(OutboxEventEntity.STATUS_PENDING);
            assertThat(bad.getRetryCount()).isEqualTo(1);
            assertThat(good.getStatus()).isEqualTo(OutboxEventEntity.STATUS_PUBLISHED);
        }
    }

    @Nested
    @DisplayName("空批次")
    class EmptyBatch {

        @Test
        @DisplayName("沒有待投遞事件時完全不碰 Kafka")
        void noPendingMeansNoKafkaCall() {
            when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of());

            assertThat(relayer.relayPendingEvents()).isZero();
            verify(kafkaTemplate, times(0)).send(any(ProducerRecord.class));
        }
    }
}
