package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ActivityView;
import com.flashsale.application.port.out.SeckillLiveCounters;

import java.time.Instant;

/** 後台的秒殺活動即時監控。 */
public interface ActivityMonitorUseCase {

    /**
     * 一次取齊「這一刻」的全部狀態。餘量與售罄標記直接讀 Redis，不經快取——
     * 監控頁看到的必須是真的，快取只會讓維運在錯的數字上做決定。
     */
    ActivityMonitorView snapshot(Long activityId);

    record ActivityMonitorView(ActivityView activity,
                               boolean soldOutMarked,
                               long queueBacklog,
                               double queueDrainRatePerSecond,
                               long queueEstimatedWaitSeconds,
                               SeckillLiveCounters.Snapshot counters,
                               Instant sampledAt) {
    }
}
