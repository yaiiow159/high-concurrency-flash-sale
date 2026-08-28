package com.flashsale.infrastructure.adapter.out.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.out.SeckillMessagePublisher;
import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.infrastructure.config.FlashSaleProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 建單訊息投遞埠的 Kafka 實作。
 *
 * <p><b>刻意同步等待 broker ack</b>。非同步投遞吞吐更高，但「已受理」這句話一旦回給使用者，
 * 就必須是真的——若訊息其實沒送到，庫存扣了、訂單卻永遠不會出現，
 * 使用者會看著一個永遠停在處理中的訂單。
 *
 * <p>逾時上限刻意設得很短（預設 500ms）：秒殺場景下，一個等 3 秒才失敗的請求
 * 會佔住 Tomcat 執行緒，比直接失敗更容易拖垮整個服務。快速失敗，讓使用者重試。
 */
@Component
public class KafkaSeckillMessagePublisher implements SeckillMessagePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final FlashSaleProperties properties;

    public KafkaSeckillMessagePublisher(KafkaTemplate<String, String> kafkaTemplate,
                                        ObjectMapper objectMapper,
                                        FlashSaleProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(SeckillOrderMessage message) {
        String payload = serialize(message);
        try {
            kafkaTemplate.send(KafkaTopics.ORDER_CREATE, message.partitionKey(), payload)
                    .get(properties.mq().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.MESSAGE_PUBLISH_FAILED, "訊息投遞被中斷", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new BusinessException(ErrorCode.MESSAGE_PUBLISH_FAILED,
                    "訂單訊息投遞失敗 orderNo=" + message.orderNo(), e);
        }
    }

    private String serialize(SeckillOrderMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("建單訊息序列化失敗 orderNo=" + message.orderNo(), e);
        }
    }
}
