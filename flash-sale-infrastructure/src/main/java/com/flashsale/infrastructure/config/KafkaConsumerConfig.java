package com.flashsale.infrastructure.config;

import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import com.flashsale.domain.shared.BusinessException;
import org.apache.kafka.clients.admin.NewTopic;
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
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", 0));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, exponentialBackOff());

        // 程式錯誤重試一萬次結果都一樣，只會拖住整個分區。
        // BusinessException 不在這裡——它可能帶著 C 系列的可重試錯誤碼，
        // 由下面的 backOffFunction 逐筆判斷。
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                IllegalStateException.class);

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
