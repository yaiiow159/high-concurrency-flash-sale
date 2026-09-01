package com.flashsale.infrastructure.id;

import com.flashsale.infrastructure.config.FlashSaleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Snowflake 識別碼產生器。
 *
 * <p><b>為什麼不用資料庫自增或 UUID？</b>
 * <ul>
 *   <li>資料庫自增需要一次遠端往返，在秒殺熱路徑上不可接受</li>
 *   <li>UUID 完全隨機，作為 InnoDB 主鍵會造成大量頁分裂，寫入效能隨資料量惡化</li>
 * </ul>
 * Snowflake 本地產生、單調遞增、天然帶時間資訊，是這個場景的正解。
 *
 * <p>位元配置：{@code 41 位毫秒時間戳 | 10 位節點 | 12 位序號}，
 * 單節點每毫秒可產生 4096 個 ID，支援 1024 個節點，可用至 2090 年。
 *
 * <p><b>時鐘回撥</b>是 Snowflake 唯一的死穴——NTP 校時或虛擬機遷移都可能讓時間倒退，
 * 進而產生重複 ID。這裡採「短回撥等待、長回撥拒絕」：小幅回撥（&lt;5ms）自旋等待，
 * 大幅回撥直接拋錯讓節點失敗。<b>寧可讓這個節點不可用，也不能發出重複的識別碼</b>——
 * 重複會在資料庫唯一索引上引爆，而且是在最不該出事的尖峰時刻。
 *
 * <p>訂單與付款單各自透過薄薄的配接器使用<b>同一個</b>產生器實例。
 * 兩者若各自持有實例，序號會從相同的起點開始，同一毫秒內可能產生相同的數值——
 * 雖然因為前綴不同而不會真的衝突，但那是靠命名空間僥倖，不是靠設計。
 */
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

    /**
     * 產生下一個識別碼。
     *
     * <p>{@code synchronized} 在這裡是可接受的：臨界區只有幾條算術指令，
     * 且單節點每毫秒能發 4096 個號，遠超過單機能承受的請求量。
     * 用 CAS 改寫只會讓程式更難讀，換不到實質的吞吐提升。
     */
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

    /**
     * 解出識別碼內嵌的產生時間。
     *
     * <p>這是選 Snowflake 而非 UUID 的附帶好處：ID 自帶時間資訊，
     * 對帳時不必為了知道「這筆多久以前產生的」而去查資料庫——
     * 而查資料庫正是孤兒扣減這個場景做不到的事（訂單根本不存在）。
     */
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
