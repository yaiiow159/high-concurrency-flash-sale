package com.flashsale.application.port.out.message;

import com.flashsale.application.port.in.command.SeckillCommand;

import java.time.Instant;

/**
 * 投遞到 MQ 的建單訊息——削峰的載體。
 *
 * <p>刻意<b>不</b>直接投遞領域物件：訊息是跨行程契約，一旦領域模型重構就會破壞
 * 正在佇列中的舊訊息。這個 record 是穩定的資料契約，欄位只增不改。
 *
 * @param orderNo    請求進來時就預先產生，供前端輪詢與消費端冪等
 * @param requestId  端到端冪等鍵，同時是庫存補償的憑據
 */
public record SeckillOrderMessage(
        String orderNo,
        Long activityId,
        Long userId,
        int quantity,
        String requestId,
        Instant requestedAt
) {

    public static SeckillOrderMessage of(String orderNo, SeckillCommand command, Instant requestedAt) {
        return new SeckillOrderMessage(
                orderNo,
                command.activityId(),
                command.userId(),
                command.quantity(),
                command.requestId(),
                requestedAt);
    }

    /** MQ 分區鍵：同一活動的訊息落在同一分區，方便消費端做批次與有序處理。 */
    public String partitionKey() {
        return String.valueOf(activityId);
    }
}
