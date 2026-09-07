package com.flashsale.infrastructure.adapter.out.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flashsale.application.port.out.SeckillMessagePublisher.Outcome;
import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.infrastructure.config.FlashSaleProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasAfterAppendException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 投遞結果的分類（ADR-0030）。
 *
 * <p>這個類別的全部實質內容就是「哪一種失敗算確定沒送出」——而那個判斷決定了
 * 要不要退庫，判錯就是超賣。用應用層的 mock 測不到它：那裡只驗「拿到 PENDING 會怎麼做」。
 */
@DisplayName("建單訊息投遞")
class KafkaSeckillMessagePublisherTest {

    private static final SeckillOrderMessage MESSAGE = new SeckillOrderMessage(
            "222713572851974144", 9001L, 159L, 1, "req-1", Instant.parse("2026-09-07T10:00:00Z"));

    private KafkaTemplate<String, String> kafkaTemplate;
    private CompletableFuture<SendResult<String, String>> future;
    private KafkaSeckillMessagePublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        future = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);
        // send-timeout 設得極短：測試不要真的等
        FlashSaleProperties properties = new FlashSaleProperties(
                new FlashSaleProperties.Mq(Duration.ofMillis(20)),
                null, null, null, null, null);
        publisher = new KafkaSeckillMessagePublisher(kafkaTemplate, mapper(), properties);
    }

    /** 與正式環境同一組設定：裸 ObjectMapper 序列化不了 Instant，那樣測的就不是同一件事。 */
    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private void failWith(Throwable cause) {
        future.completeExceptionally(cause);
    }

    @Test
    @DisplayName("broker 確認：ACKED")
    void ackedWhenBrokerConfirms() {
        future.complete(new SendResult<>(
                new ProducerRecord<>(KafkaTopics.ORDER_CREATE, "k", "v"),
                new RecordMetadata(new TopicPartition(KafkaTopics.ORDER_CREATE, 0), 0, 0, 0L, 0, 0)));

        assertThat(publisher.publish(MESSAGE)).isEqualTo(Outcome.ACKED);
    }

    @Test
    @DisplayName("等不到結果：PENDING——生產者仍在重試，不可據此退庫")
    void pendingWhenWaitTimesOut() {
        // future 永不完成，模擬「我們不等了，但生產者還在送」

        assertThat(publisher.publish(MESSAGE)).isEqualTo(Outcome.PENDING);
    }

    @Test
    @DisplayName("已 append 但副本不足：PENDING——ISR 恢復後那筆紀錄會變成可見")
    void pendingWhenAppendedButUnderReplicated() {
        // 這一條是 ADR-0030 的核心：retries 被調小時，這種錯誤會提早變成終局的
        // ExecutionException。當成失敗而退庫，庫存會被別人買走而訂單稍後照樣建立
        failWith(new NotEnoughReplicasAfterAppendException("ISR 不足"));

        assertThat(publisher.publish(MESSAGE)).isEqualTo(Outcome.PENDING);
    }

    @Test
    @DisplayName("網路錯誤：PENDING——送出去了沒無從得知")
    void pendingOnNetworkError() {
        failWith(new NetworkException("連線中斷"));

        assertThat(publisher.publish(MESSAGE)).isEqualTo(Outcome.PENDING);
    }

    @Test
    @DisplayName("訊息過大：確定被拒，可以安全退庫")
    void failsWhenRecordRejected() {
        failWith(new RecordTooLargeException("超過上限"));

        assertThatThrownBy(() -> publisher.publish(MESSAGE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MESSAGE_PUBLISH_FAILED);
    }

    @Test
    @DisplayName("沒有權限：確定被拒，可以安全退庫")
    void failsWhenUnauthorized() {
        failWith(new TopicAuthorizationException("無權寫入"));

        assertThatThrownBy(() -> publisher.publish(MESSAGE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MESSAGE_PUBLISH_FAILED);
    }

    @Test
    @DisplayName("可重試的錯誤被包在別的例外裡：仍然算 PENDING")
    void pendingWhenRetriableIsWrapped() {
        failWith(new IllegalStateException("外層", new NetworkException("內層")));

        assertThat(publisher.publish(MESSAGE)).isEqualTo(Outcome.PENDING);
    }

    @Test
    @DisplayName("序列化失敗屬於程式錯誤，不是投遞失敗")
    void serializationIsProgrammingError() {
        ObjectMapper broken = mock(ObjectMapper.class);
        KafkaSeckillMessagePublisher withBrokenMapper = new KafkaSeckillMessagePublisher(
                kafkaTemplate, broken, new FlashSaleProperties(
                        new FlashSaleProperties.Mq(Duration.ofMillis(20)),
                        null, null, null, null, null));
        try {
            when(broken.writeValueAsString(any())).thenThrow(
                    new com.fasterxml.jackson.core.JsonProcessingException("壞掉") { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThatThrownBy(() -> withBrokenMapper.publish(MESSAGE))
                .isInstanceOf(IllegalStateException.class);
    }
}
