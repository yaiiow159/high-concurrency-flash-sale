package com.flashsale.application.port.out.message;

import com.flashsale.application.port.in.command.SeckillCommand;

import java.time.Instant;

/** 投遞到 MQ 的建單訊息——削峰的載體。 */
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

    /** MQ 分區鍵：<b>訂單號</b>。 */
    public String partitionKey() {
        return orderNo;
    }
}
