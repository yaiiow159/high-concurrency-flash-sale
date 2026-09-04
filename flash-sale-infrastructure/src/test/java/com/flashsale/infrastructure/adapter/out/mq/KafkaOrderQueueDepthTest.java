package com.flashsale.infrastructure.adapter.out.mq;

import com.flashsale.infrastructure.config.AdmissionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 入場控制的判斷邏輯（ADR-0023）。
 *
 * <p>這裡測的不是「能不能連上 Kafka」，而是<b>資料不完整時會不會誤擋人</b>。
 * 誤擋的代價是合法請求被拒絕，比「收太多」嚴重得多，
 * 而它不會拋任何例外——只會有人買不到東西。
 */
@DisplayName("建單佇列深度")
class KafkaOrderQueueDepthTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);

    private static KafkaOrderQueueDepth queueDepth(boolean enabled, long maxWaitSeconds) {
        return new KafkaOrderQueueDepth(
                mock(AdminClient.class),
                new AdmissionProperties(enabled, maxWaitSeconds, 5000,
                        "seckill-order-creator", "seckill.order.create"),
                new SimpleMeterRegistry(),
                CLOCK);
    }

    @Nested
    @DisplayName("等待時間的推估")
    class Estimation {

        @Test
        @DisplayName("還沒量到任何值時，積壓為 0、等待為 0")
        void initialState() {
            KafkaOrderQueueDepth depth = queueDepth(true, 300);

            assertThat(depth.backlog()).isZero();
            assertThat(depth.estimatedWaitSeconds()).isZero();
            assertThat(depth.drainRatePerSecond()).isZero();
        }

        @Test
        @DisplayName("有積壓但速率未知時回 -1，不可回 0")
        void unknownRateIsNotZeroWait() {
            // 回 0 會被讀成「不用等」，然後讓人等四十分鐘。
            // 誠實說「算不出來」比給一個好看的假數字好
            KafkaOrderQueueDepth depth = queueDepth(true, 300);
            depth.setStateForTest(10_000, 0);

            assertThat(depth.estimatedWaitSeconds()).isEqualTo(-1);
        }

        @Test
        @DisplayName("等待時間 = 積壓 ÷ 速率，無條件進位")
        void waitIsBacklogOverRate() {
            KafkaOrderQueueDepth depth = queueDepth(true, 300);
            depth.setStateForTest(1_000, 182.0);

            // 1000 / 182 = 5.49 -> 6
            assertThat(depth.estimatedWaitSeconds()).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("擋不擋人")
    class Admission {

        @Test
        @DisplayName("預設關閉：積壓再深也不擋")
        void disabledByDefault() {
            // 會主動拒絕合法請求的機制，先讓指標跑一段時間再打開
            KafkaOrderQueueDepth depth = queueDepth(false, 300);
            depth.setStateForTest(1_000_000, 1.0);

            assertThat(depth.isOverloaded()).isFalse();
        }

        @Test
        @DisplayName("超過等待閾值才擋")
        void blocksOnlyBeyondThreshold() {
            KafkaOrderQueueDepth depth = queueDepth(true, 300);

            depth.setStateForTest(299, 1.0);
            assertThat(depth.isOverloaded()).isFalse();

            depth.setStateForTest(301, 1.0);
            assertThat(depth.isOverloaded()).isTrue();
        }

        @Test
        @DisplayName("算不出等待時間時不擋人——寧可收太多，也不要誤擋")
        void doesNotBlockWhenUnknown() {
            // 速率未知（剛啟動、或 Kafka 查詢一直失敗）時，
            // 擋人等於因為自己的監控故障而拒絕合法請求
            KafkaOrderQueueDepth depth = queueDepth(true, 300);
            depth.setStateForTest(1_000_000, 0);

            assertThat(depth.estimatedWaitSeconds()).isEqualTo(-1);
            assertThat(depth.isOverloaded()).isFalse();
        }

        @Test
        @DisplayName("沒有積壓就不擋，即使速率是 0")
        void noBacklogNeverBlocks() {
            // 系統閒著沒事做時速率本來就是 0，那不是異常
            KafkaOrderQueueDepth depth = queueDepth(true, 300);
            depth.setStateForTest(0, 0);

            assertThat(depth.isOverloaded()).isFalse();
        }
    }
}
