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

/**
 * Kafka 消費端的重試與死信策略。
 *
 * <p><b>重試策略的核心判斷：哪些錯誤該重試，哪些不該。</b>
 * <ul>
 *   <li><b>可重試</b>：資料庫連線中斷、鎖等待逾時等暫時性故障——等一下再試就會好</li>
 *   <li><b>不可重試</b>：業務規則拒絕（活動不存在、資料格式錯誤）——
 *       重試一萬次結果都一樣，只會拖住整個分區的消費進度</li>
 * </ul>
 * 把不可重試的錯誤直接送進 DLQ，是保護消費吞吐的關鍵；
 * 一則毒藥訊息（poison message）足以讓整個分區停擺。
 *
 * <p>退避採指數增長而非固定間隔：故障通常需要時間恢復，
 * 固定 1 秒重試 5 次只會在 5 秒內把下游再打 5 次，往往讓故障更難恢復。
 *
 * <h2>分類的依據是 {@code ErrorCode}，不是例外型別</h2>
 *
 * <p>先前是「{@code BusinessException} 一律不重試」。那個規則對大多數情況成立，
 * 但 {@code ErrorCode} 本身就分了 C 系列（系統故障，可重試）與 A/B 系列
 * （呼叫端或業務規則錯誤，不可重試）——用型別分類等於把這個既有的區分丟掉。
 *
 * <p>代價是真實的：退款消費端在閘道暫時故障時丟出例外，
 * 原意是「讓 Kafka 重試」，但因為型別落在不可重試清單裡，
 * <b>第一次就直接進死信</b>，而那筆退款的付款紀錄早已 commit 成「已退」。
 * 帳上退了、錢沒退，且沒有任何東西會再提醒。
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    private static final long INITIAL_BACKOFF_MILLIS = 500L;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_BACKOFF_MILLIS = 10_000L;
    private static final int MAX_ATTEMPTS = 4;

    /** 不重試：第一次失敗就交給死信處理。 */
    private static final FixedBackOff NO_RETRY = new FixedBackOff(0L, 0L);

    @Bean
    public NewTopic orderCreateTopic() {
        // 分區數決定消費端的最大並行度。12 是一個能被 1/2/3/4/6 整除的數字，
        // 讓消費者副本數在擴縮容時都能均勻分配分區。
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

    /**
     * 這個例外該不該重試。
     *
     * <p>Kafka 會把消費端的例外包在 {@code ListenerExecutionFailedException} 裡，
     * 因此要往下找根因；找不到 {@link BusinessException} 就當作暫時性故障重試——
     * <b>預設重試而不是預設放棄</b>，因為放棄的代價（訊息靜默消失）
     * 遠大於多試幾次。
     */
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
