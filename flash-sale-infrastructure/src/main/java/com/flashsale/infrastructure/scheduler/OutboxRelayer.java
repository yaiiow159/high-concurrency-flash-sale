package com.flashsale.infrastructure.scheduler;

import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import com.flashsale.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.OutboxEventJpaRepository;
import com.flashsale.infrastructure.config.FlashSaleProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 中繼的實際執行者。
 *
 * <p><b>為什麼要從 {@link OutboxRelayScheduler} 拆出來？</b>
 * Spring 的 {@code @Transactional} 是靠動態代理實現的，同一個 Bean 內部的方法呼叫
 * （{@code this.method()}）走的是原始物件，<b>完全不會經過代理</b>——
 * 註解會安靜地失效，交易根本沒開，而且沒有任何錯誤訊息。
 *
 * <p>把交易方法放到另一個 Bean 並透過依賴注入呼叫，代理才會生效。
 * 這是 Spring 最常見也最難察覺的陷阱之一，值得為它多一個類別。
 */
@Component
public class OutboxRelayer {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayer.class);

    /**
     * <b>整批</b>的投遞期限，不是每一筆的。
     *
     * <p>先前是每筆各自等 5 秒，於是一批 200 筆在 Kafka 變慢時最壞要等 1000 秒——
     * 而這段時間交易一直開著、佔著一條連線，InnoDB 的 read view 也放不掉。
     *
     * <p>現在全部先送出再一起收，所有 future 幾乎同時完成（它們本來就在
     * 同幾個生產者批次裡），第一筆的等待就吸收掉大部分時間，其餘立刻返回。
     * 因此期限可以放寬到 10 秒仍然遠比先前安全：最壞情況從 1000 秒降到 10 秒。
     */
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxEventJpaRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FlashSaleProperties properties;
    private final Clock clock;

    public OutboxRelayer(OutboxEventJpaRepository outboxRepository,
                         KafkaTemplate<String, String> kafkaTemplate,
                         FlashSaleProperties properties,
                         Clock clock) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 搬運一批待投遞事件。
     *
     * <h2>先全部送出，再一起收結果</h2>
     *
     * <p>先前是逐筆 {@code send().get()}。那個寫法的註解說
     * 「批次非同步投遞無法精確知道哪幾筆成功」——<b>那是錯的</b>：
     * 每一次 {@code send()} 各自回傳一個 future，逐筆記錄成敗完全做得到。
     *
     * <p>而它的代價很實在。生產者設了 {@code linger.ms=5}，
     * 那個攢批對熱路徑是划算的（多個執行緒同時投遞，批次瞬間填滿）；
     * 但中繼器是單執行緒逐筆等 ack，<b>每一筆都自己獨佔一個批次</b>，
     * linger 從攤提成本變成每筆固定 +5ms。加上 acks=all 的往返，
     * 200 筆一輪約 1.3 秒；再乘上 {@code OutboxRelayScheduler} 的單節點鎖，
     * 全叢集的天花板約每秒 90 筆，而且加機器也提不上去。
     *
     * <p>一場賣掉 5000 件的秒殺要 57 秒才排空——不會超賣（對帳寬限期遠大於此），
     * 但退庫、通知、出貨整條慢車道都跟著延遲，而使用者正在輪詢等訂單。
     *
     * <p>改成兩階段之後，200 次序列往返塌縮成一兩個批次。
     *
     * <h2>順序仍然有保證</h2>
     *
     * <p>非同步併發送出通常會有重排風險（某一筆重試時後面的已經先到），
     * 但這裡不會：生產者開了 {@code enable.idempotence=true} 且
     * {@code max.in.flight.requests.per.connection=5}，
     * broker 會依序號拒絕亂序的批次。
     * <b>這個保證是這次改動能成立的前提</b>——若有人把冪等生產者關掉，
     * 同一訂單的事件順序就會在重試時silently 亂掉，
     * 而退款 Saga 正是靠「同一訂單的事件有序」在運作的。
     *
     * @return 本次成功投遞的筆數
     */
    @Transactional
    public int relayPendingEvents() {
        List<OutboxEventEntity> pending =
                outboxRepository.findPending(Limit.of(properties.outbox().batchSize()));
        if (pending.isEmpty()) {
            return 0;
        }

        List<InFlight> inFlight = new ArrayList<>(pending.size());
        for (OutboxEventEntity event : pending) {
            dispatch(event).ifPresent(inFlight::add);
        }

        Instant deadline = clock.instant().plus(BATCH_TIMEOUT);
        int published = 0;
        for (InFlight sent : inFlight) {
            if (awaitAck(sent, deadline)) {
                published++;
            }
        }
        log.debug("Outbox 中繼完成：{}/{} 筆投遞成功", published, pending.size());
        return published;
    }

    /**
     * 送出但不等待。
     *
     * <p>{@code send()} 本身仍可能同步拋出——序列化失敗，或生產者緩衝區滿了
     * 超過 {@code max.block.ms}。那類失敗沒有 future 可等，就地標記。
     */
    private Optional<InFlight> dispatch(OutboxEventEntity event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaTopics.ORDER_EVENT, event.getAggregateId(), event.getPayload());
            // 事件型別放在標頭，消費端不必反序列化 payload 就能決定要不要處理。
            record.headers().add(KafkaTopics.HEADER_EVENT_TYPE,
                    event.getEventType().getBytes(StandardCharsets.UTF_8));
            return Optional.of(new InFlight(event, kafkaTemplate.send(record)));
        } catch (RuntimeException e) {
            markFailed(event, e);
            return Optional.empty();
        }
    }

    /**
     * 等一筆的 ack。
     *
     * <p>共用整批的期限而不是每筆各自計時：所有 future 幾乎同時完成，
     * 第一筆就吸收掉大部分等待，其餘立刻返回。這讓最壞情況從
     * 「筆數 × 逾時」變成一個固定上限。
     */
    private boolean awaitAck(InFlight sent, Instant deadline) {
        long remaining = Duration.between(clock.instant(), deadline).toMillis();
        try {
            sent.future().get(Math.max(remaining, 0), TimeUnit.MILLISECONDS);
            sent.event().markPublished(clock.instant());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sent.event().markFailed("投遞被中斷", properties.outbox().maxRetry());
            return false;
        } catch (Exception e) {
            markFailed(sent.event(), e);
            return false;
        }
    }

    private void markFailed(OutboxEventEntity event, Exception cause) {
        event.markFailed(cause.getMessage(), properties.outbox().maxRetry());
        log.warn("Outbox 事件 {} 投遞失敗（第 {} 次）",
                event.getEventId(), event.getRetryCount(), cause);
    }

    /** 已送出、等待 ack 的一筆。把事件與它自己的 future 綁在一起，才能逐筆記錄成敗。 */
    private record InFlight(OutboxEventEntity event,
                            CompletableFuture<SendResult<String, String>> future) {
    }

    /** 清理已投遞的舊紀錄，避免發件匣表隨訂單量無限成長。 */
    @Transactional
    public int deleteOldPublishedEvents() {
        int retentionDays = properties.outbox().retentionDays();
        int deleted = outboxRepository.deletePublishedBefore(
                clock.instant().minus(Duration.ofDays(retentionDays)));
        if (deleted > 0) {
            log.info("清理 {} 天前已投遞的 Outbox 紀錄：{} 筆", retentionDays, deleted);
        }
        return deleted;
    }
}
