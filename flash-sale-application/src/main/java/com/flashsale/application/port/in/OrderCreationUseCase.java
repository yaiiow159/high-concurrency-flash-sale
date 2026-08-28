package com.flashsale.application.port.in;

import com.flashsale.application.port.out.message.SeckillOrderMessage;

/**
 * 建單入站埠——由 MQ 消費端驅動，是削峰後的「慢車道」。
 *
 * <p>實作必須<b>冪等</b>：MQ 是至少一次語意，同一則訊息重複投遞是常態而非異常。
 */
public interface OrderCreationUseCase {

    /**
     * 依據建單訊息落庫。
     *
     * @return {@code true} 表示本次確實建立了新訂單；{@code false} 表示訊息重複、已略過
     * @throws RuntimeException 可重試的故障（DB 不可用等），交由消費端的重試與 DLQ 機制處理
     */
    boolean createFrom(SeckillOrderMessage message);
}
