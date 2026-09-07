package com.flashsale.infrastructure.adapter.out.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.out.SeckillMessagePublisher;
import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.infrastructure.config.FlashSaleProperties;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 建單訊息投遞埠的 Kafka 實作。 */
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
            // 紀錄早已進了 accumulator，生產者不受本執行緒被中斷影響——與逾時同一個立場
            log.warn("等待 broker 確認被中斷 orderNo={}", message.orderNo());
            return Outcome.PENDING;
        } catch (ExecutionException e) {
            return classify(message, e);
        }
    }

    /**
     * 生產者放棄了，但「放棄」不一定等於「沒送出」。
     *
     * <p>可重試的錯誤代表結果未知——{@code NotEnoughReplicasAfterAppendException} 的字面意思
     * 就是「已經 append 到 leader 但副本數不足」，那筆紀錄在 ISR 恢復後會變成可見。
     * 把它當成失敗而退庫，就回到了 ADR-0030 要修掉的那條超賣路徑。
     *
     * <p>這一層讓正確性不再依賴生產者的參數搭配：就算有人把 {@code retries} 調小、
     * 讓可重試的錯誤提早變成終局，這裡仍然會判成 PENDING。
     */
    private Outcome classify(SeckillOrderMessage message, ExecutionException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof RetriableException) {
                log.warn("投遞遇到可重試的錯誤，結果未知 orderNo={}, cause={}",
                        message.orderNo(), cause.getClass().getSimpleName());
                return Outcome.PENDING;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        // 序列化、訊息過大、主題不存在、沒有權限——這些在 broker 端連 append 都不會發生
        throw new BusinessException(ErrorCode.MESSAGE_PUBLISH_FAILED,
                "訂單訊息投遞失敗 orderNo=" + message.orderNo(), e);
    }

    private String serialize(SeckillOrderMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("建單訊息序列化失敗 orderNo=" + message.orderNo(), e);
        }
    }
}
