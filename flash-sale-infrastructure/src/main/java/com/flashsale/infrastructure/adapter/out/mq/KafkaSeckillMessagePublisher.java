package com.flashsale.infrastructure.adapter.out.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.out.SeckillMessagePublisher;
import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.infrastructure.config.FlashSaleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaSeckillMessagePublisher implements SeckillMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaSeckillMessagePublisher.class);

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
    public Outcome publish(SeckillOrderMessage message) {
        String payload = serialize(message);
        try {
            kafkaTemplate.send(KafkaTopics.ORDER_CREATE, message.partitionKey(), payload)
                    .get(properties.mq().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return Outcome.ACKED;
        } catch (TimeoutException e) {
            // **不是失敗，是不知道。** send-timeout 只是我們願意等的時間；
            // 生產者仍會在 delivery.timeout.ms 內重試，這筆訊息很可能之後才送達。
            // 據此退庫的話，庫存會被別人買走、而訂單稍後照樣建立——那是真實超賣（ADR-0030）。
            log.warn("等待 broker 確認逾時，生產者仍在重試 orderNo={}", message.orderNo());
            return Outcome.PENDING;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 本執行緒被中斷，送出與否同樣未知——與逾時同一個立場
            log.warn("等待 broker 確認被中斷 orderNo={}", message.orderNo());
            return Outcome.PENDING;
        } catch (ExecutionException e) {
            // 生產者已放棄，確定沒送出：這才是可以安全退庫的情況
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
