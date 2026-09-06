package com.flashsale.infrastructure.id;

import com.flashsale.infrastructure.config.FlashSaleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** Snowflake 識別碼產生器。 */
@Component
public class SnowflakeIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeIdGenerator.class);

    /** 起始紀元：2025-01-01T00:00:00Z。往後推可延長 41 位時間戳的可用年限。 */
    private static final long EPOCH_MILLIS = 1_735_689_600_000L;

    private static final long NODE_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_NODE_ID = (1L << NODE_ID_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long NODE_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + NODE_ID_BITS;

    /** 可容忍的時鐘回撥上限，超過即拒絕發號。 */
    private static final long MAX_TOLERABLE_BACKWARD_MILLIS = 5L;

    private final long nodeId;
    private final Clock clock;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(FlashSaleProperties properties, Clock clock) {
        long configuredNodeId = properties.snowflake().nodeId();
        if (configuredNodeId < 0 || configuredNodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException(
                    "snowflake.node-id 必須介於 0 與 %d 之間，目前為 %d".formatted(MAX_NODE_ID, configuredNodeId));
        }
        this.nodeId = configuredNodeId;
        this.clock = clock;
        log.info("Snowflake 識別碼產生器啟動，節點編號={}", nodeId);
    }

    /** 產生下一個識別碼。 */
    public synchronized long nextId() {
        long timestamp = awaitNextValidTimestamp();

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 本毫秒的 4096 個號已用盡，自旋到下一毫秒
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;

        return ((timestamp - EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                | (nodeId << NODE_ID_SHIFT)
                | sequence;
    }

    /** 解出識別碼內嵌的產生時間。 */
    public Optional<Instant> timestampOf(String rawId) {
        try {
            long id = Long.parseLong(rawId);
            if (id <= 0) {
                return Optional.empty();
            }
            return Optional.of(Instant.ofEpochMilli((id >>> TIMESTAMP_SHIFT) + EPOCH_MILLIS));
        } catch (NumberFormatException e) {
            // 識別碼不是本產生器發的（例如資料遷移自舊系統）——回報無法解析，
            // 讓呼叫端保守處理，而不是猜一個時間出來
            return Optional.empty();
        }
    }

    private long awaitNextValidTimestamp() {
        long timestamp = clock.millis();
        if (timestamp >= lastTimestamp) {
            return timestamp;
        }

        long backwardMillis = lastTimestamp - timestamp;
        if (backwardMillis > MAX_TOLERABLE_BACKWARD_MILLIS) {
            throw new IllegalStateException(
                    "偵測到時鐘回撥 %d ms，超過容忍上限，拒絕發號以避免產生重複識別碼".formatted(backwardMillis));
        }
        log.warn("偵測到輕微時鐘回撥 {} ms，等待追平", backwardMillis);
        return waitUntilNextMillis(lastTimestamp);
    }

    private long waitUntilNextMillis(long lastTimestamp) {
        long timestamp = clock.millis();
        while (timestamp <= lastTimestamp) {
            Thread.onSpinWait();
            timestamp = clock.millis();
        }
        return timestamp;
    }
}
