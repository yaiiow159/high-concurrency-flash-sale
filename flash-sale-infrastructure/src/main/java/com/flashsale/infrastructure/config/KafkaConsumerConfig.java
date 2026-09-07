package com.flashsale.infrastructure.config;

import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import com.flashsale.domain.shared.BusinessException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/** Kafka 消費端的重試與死信策略。 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    private static final long INITIAL_BACKOFF_MILLIS = 500L;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_BACKOFF_MILLIS = 10_000L;
    private static final int MAX_ATTEMPTS = 4;

    /** 不重試：第一次失敗就交給死信處理。 */
    private static final FixedBackOff NO_RETRY = new FixedBackOff(0L, 0L);
    private static final String DLT_SUFFIX = ".DLT";

    /** 建單主題。 */
    @Bean
    public NewTopic orderCreateTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_CREATE).partitions(12).replicas(1).build();
    }

    @Bean
    public NewTopic orderCreateDltTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_CREATE_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_EVENT).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventDltTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_EVENT_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations,
                // 死信分區固定為 0：DLQ 的量極小，維持分區與原 topic 一致只會產生大量空分區。
                //
                // **已經在 DLT 上的訊息不再往下丟。** 無條件加後綴會解析出
                // `xxx.DLT.DLT`，那個 topic 沒有宣告而 auto-create 是關的——
                // recoverer 發布失敗 → offset 不提交 → 同一筆無限重投，
                // 整個 DLT 分區停住，後面每一筆死信都拿不到補償。
                (record, exception) -> resolveDeadLetter(record));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, exponentialBackOff());

        // 程式錯誤重試一萬次結果都一樣，只會拖住整個分區。
        // BusinessException 不在這裡——它可能帶著 C 系列的可重試錯誤碼，
        // 由下面的 backOffFunction 逐筆判斷。
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                IllegalStateException.class,
                // 解析不了的 payload 重試一萬次結果都一樣。它繼承 IOException，
                // 不列出來的話會落進 isRetryable 的預設 true
                com.fasterxml.jackson.core.JsonProcessingException.class);

        // 逐筆決定要不要重試。回傳零次嘗試的退避＝立刻進死信。
        // 用函式而非型別清單，才能讀到 ErrorCode 上既有的可重試標記。
        handler.setBackOffFunction((record, exception) ->
                isRetryable(exception) ? exponentialBackOff() : NO_RETRY);

        handler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("消費失敗，第 {} 次重試 topic={}, offset={}",
                        deliveryAttempt, record.topic(), record.offset(), exception));

        return handler;
    }

    /** 這個例外該不該重試。 */
    /**
     * 已經在死信 topic 上的訊息不再往下丟：回 {@code null} 讓 recoverer 略過並提交位移。
     *
     * <p>無條件加後綴會解析出 {@code xxx.DLT.DLT}，而那個 topic 沒有宣告、
     * auto-create 又是關的——發布失敗會讓位移永不提交，同一筆無限重投，
     * 整個死信分區停住，後面每一筆都拿不到補償。
     */
    private static TopicPartition resolveDeadLetter(ConsumerRecord<?, ?> record) {
        if (record.topic().endsWith(DLT_SUFFIX)) {
            // 走到這裡代表死信的補償本身也失敗了，需要人工介入
            log.error("死信處理失敗且已在死信 topic 上，丟棄不再轉投 topic={}, offset={}",
                    record.topic(), record.offset());
            return null;
        }
        return new TopicPartition(record.topic() + DLT_SUFFIX, 0);
    }

    private static boolean isRetryable(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof BusinessException business) {
                return business.errorCode().retryable();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return true;
    }

    private ExponentialBackOff exponentialBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_BACKOFF_MILLIS, BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MILLIS);
        backOff.setMaxAttempts(MAX_ATTEMPTS);
        return backOff;
    }
}
