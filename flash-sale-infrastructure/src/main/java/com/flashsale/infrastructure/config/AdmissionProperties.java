package com.flashsale.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 入場控制（ADR-0023）。
 *
 * @param enabled        <b>預設關閉</b>。與對帳的自動修復同一個立場：
 *                       會主動拒絕合法請求的機制，先讓指標跑一段時間、
 *                       確認閾值訂得合理，再打開
 * @param maxWaitSeconds 閾值以<b>等待時間</b>表示而非訊息數——訊息數在
 *                       消費端加機器之後就失去意義，而「使用者願意等幾分鐘」
 *                       是一個可以跟產品討論的單位
 * @param sampleInterval 取樣間隔（毫秒）。熱路徑讀的是這個間隔更新一次的快取值，
 *                       因此尖峰的前一個間隔擋不住——那是「熱路徑零往返」的代價
 */
@ConfigurationProperties(prefix = "flash-sale.admission")
public record AdmissionProperties(

        @DefaultValue("false") boolean enabled,
        @DefaultValue("300") long maxWaitSeconds,
        @DefaultValue("5000") long sampleIntervalMillis,
        @DefaultValue("seckill-order-creator") String consumerGroup,
        @DefaultValue("seckill.order.create") String topic
) {
}
