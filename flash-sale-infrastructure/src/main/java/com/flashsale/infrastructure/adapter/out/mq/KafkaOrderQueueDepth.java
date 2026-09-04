package com.flashsale.infrastructure.adapter.out.mq;

import com.flashsale.application.port.out.OrderQueueDepth;
import com.flashsale.infrastructure.config.AdmissionProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 建單佇列深度（ADR-0023）。
 *
 * <h2>問 broker，不問消費端</h2>
 *
 * <p>Kafka 客戶端自己會報 {@code records-lag-max}，但那個值只涵蓋
 * 該消費者<b>當下抓取到的分區</b>，而且消費者掛掉時指標會直接消失——
 * 而消費者掛掉正是最需要看到積壓的時候。
 *
 * <p>這裡改用 {@link AdminClient} 比對「消費組已提交的位移」與
 * 「日誌結尾位移」，與消費端的死活無關。
 *
 * <h2>速率取實測值</h2>
 *
 * <p>等待時間 = 積壓 ÷ 速率，而速率如果寫在設定檔裡，
 * 消費端加了機器之後那個數字就是錯的。這裡用兩次取樣之間
 * 位移的推進量現算，會自己跟上。
 */
@Component
public class KafkaOrderQueueDepth implements OrderQueueDepth {

    private static final Logger log = LoggerFactory.getLogger(KafkaOrderQueueDepth.class);

    /** 尚無資料時的哨兵值。 */
    private static final long UNKNOWN = -1L;

    private final AdminClient adminClient;
    private final AdmissionProperties properties;
    private final Clock clock;

    private final AtomicLong backlog = new AtomicLong(0);
    /** 速率用整數千分之一儲存，避免 AtomicDouble 的相依。 */
    private final AtomicLong drainRateMilli = new AtomicLong(0);

    private long lastConsumedOffsetSum = UNKNOWN;
    private Instant lastSampledAt;

    public KafkaOrderQueueDepth(AdminClient adminClient, AdmissionProperties properties,
                                MeterRegistry registry, Clock clock) {
        this.adminClient = adminClient;
        this.properties = properties;
        this.clock = clock;

        Gauge.builder("seckill_order_create_lag", backlog, AtomicLong::get)
                .description("建單佇列的積壓訊息數")
                .register(registry);
        Gauge.builder("seckill_order_create_drain_rate", this,
                        self -> self.drainRatePerSecond())
                .description("最近實測的建單速率（每秒）")
                .register(registry);
        Gauge.builder("seckill_order_create_wait_seconds", this,
                        self -> Math.max(self.estimatedWaitSeconds(), 0))
                .description("依積壓與速率推估的等待秒數")
                .register(registry);
    }

    @Override
    public long backlog() {
        return backlog.get();
    }

    @Override
    public double drainRatePerSecond() {
        return drainRateMilli.get() / 1000.0;
    }

    @Override
    public long estimatedWaitSeconds() {
        double rate = drainRatePerSecond();
        long pending = backlog.get();
        if (pending == 0) {
            return 0;
        }
        // 速率還沒量到就說「不知道」，而不是回一個看起來很小的數字。
        // 顯示「約 0 分鐘」然後讓人等四十分鐘，比誠實說不知道更糟
        if (rate <= 0) {
            return UNKNOWN;
        }
        return (long) Math.ceil(pending / rate);
    }

    @Override
    public boolean isOverloaded() {
        if (!properties.enabled()) {
            return false;
        }
        long wait = estimatedWaitSeconds();
        // 算不出等待時間時**不擋人**。入場控制失效的代價是「收太多」，
        // 誤擋的代價是「合法請求被拒絕」——後者嚴重得多
        return wait != UNKNOWN && wait > properties.maxWaitSeconds();
    }

    /**
     * 取樣一次。由排程觸發（見 {@code QueueDepthScheduler}）。
     *
     * <p><b>失敗時保留上一次的值，不歸零。</b> 歸零會讓入場控制在
     * Kafka 不穩的當下自動放行——而那正是最不該放行的時候。
     */
    public void sample() {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(properties.consumerGroup())
                    .partitionsToOffsetAndMetadata()
                    .get()
                    .entrySet().stream()
                    .filter(entry -> entry.getKey().topic().equals(properties.topic()))
                    .filter(entry -> entry.getValue() != null)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            if (committed.isEmpty()) {
                return;
            }

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = adminClient
                    .listOffsets(committed.keySet().stream()
                            .collect(Collectors.toMap(partition -> partition, p -> OffsetSpec.latest())))
                    .all().get();

            long totalLag = 0;
            long consumedSum = 0;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
                long consumed = entry.getValue().offset();
                consumedSum += consumed;
                ListOffsetsResult.ListOffsetsResultInfo end = endOffsets.get(entry.getKey());
                if (end != null) {
                    totalLag += Math.max(0, end.offset() - consumed);
                }
            }

            backlog.set(totalLag);
            updateDrainRate(consumedSum);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 保留上一次的值。歸零會讓入場控制在 Kafka 不穩時自動放行
            log.warn("取樣建單佇列深度失敗，沿用上一次的值 backlog={}", backlog.get(), e);
        }
    }

    /**
     * 直接設定狀態，<b>僅供測試</b>。
     *
     * <p>入場控制的判斷邏輯（尤其是「資料不完整時不擋人」）必須能單獨驗證，
     * 而那不該需要一個真的 Kafka——那會讓這幾條測試變成整合測試，
     * 跑得慢、而且會因為環境而紅。
     */
    void setStateForTest(long backlogValue, double drainRate) {
        backlog.set(backlogValue);
        drainRateMilli.set(Math.round(drainRate * 1000));
    }

    private void updateDrainRate(long consumedSum) {
        Instant now = clock.instant();
        if (lastSampledAt != null && lastConsumedOffsetSum != UNKNOWN) {
            long elapsedMillis = Duration.between(lastSampledAt, now).toMillis();
            long advanced = consumedSum - lastConsumedOffsetSum;
            // 位移倒退代表消費組被重設過，這一次不列入計算
            if (elapsedMillis > 0 && advanced >= 0) {
                double rate = advanced * 1000.0 / elapsedMillis;
                // 指數平滑：單次取樣會因為排程抖動而劇烈跳動，
                // 而這個值要拿來決定「擋不擋人」，不能讓它一下子暴衝
                double smoothed = drainRateMilli.get() == 0
                        ? rate
                        : drainRatePerSecond() * 0.7 + rate * 0.3;
                drainRateMilli.set(Math.round(smoothed * 1000));
            }
        }
        lastConsumedOffsetSum = consumedSum;
        lastSampledAt = now;
    }
}
