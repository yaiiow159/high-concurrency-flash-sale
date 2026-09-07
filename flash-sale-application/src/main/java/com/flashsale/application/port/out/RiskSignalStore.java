package com.flashsale.application.port.out;

import com.flashsale.domain.risk.RiskSignals;

import java.time.Instant;

/**
 * 收集風險訊號。實作必須：把這一次的觀察記進去之後回傳「含這一次」的計數；
 * 訊號只保留一個短窗口（自動過期），因為它們描述的是「此刻正在發生的事」，不是歷史。
 */
public interface RiskSignalStore {

    RiskSignals observe(Observation observation);

    record Observation(Long userId, Long activityId, String clientIp, String deviceId,
                       Instant accountCreatedAt, Instant now) {
    }
}
