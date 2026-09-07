package com.flashsale.application.service;

import com.flashsale.application.port.in.ActivityMonitorUseCase;
import com.flashsale.application.port.in.ActivityQueryUseCase;
import com.flashsale.application.port.in.dto.ActivityView;
import com.flashsale.application.port.out.OrderQueueDepth;
import com.flashsale.application.port.out.SeckillLiveCounters;
import com.flashsale.application.port.out.SoldOutMarker;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** 後台的秒殺活動即時監控。 */
@Service
public class ActivityMonitorService implements ActivityMonitorUseCase {

    private final ActivityQueryUseCase activityQuery;
    private final SoldOutMarker soldOutMarker;
    private final OrderQueueDepth queueDepth;
    private final SeckillLiveCounters counters;
    private final Clock clock;

    public ActivityMonitorService(ActivityQueryUseCase activityQuery,
                                  SoldOutMarker soldOutMarker,
                                  OrderQueueDepth queueDepth,
                                  SeckillLiveCounters counters,
                                  Clock clock) {
        this.activityQuery = activityQuery;
        this.soldOutMarker = soldOutMarker;
        this.queueDepth = queueDepth;
        this.counters = counters;
        this.clock = clock;
    }

    @Override
    public ActivityMonitorView snapshot(Long activityId) {
        // findById 的餘量就是 Redis 當下的值，後台活動列表也是這樣拿的
        ActivityView activity = activityQuery.findById(activityId);
        return new ActivityMonitorView(
                activity,
                soldOutMarker.isSoldOut(activityId),
                queueDepth.backlog(),
                queueDepth.drainRatePerSecond(),
                Math.max(queueDepth.estimatedWaitSeconds(), 0),
                counters.read(activityId),
                clock.instant());
    }
}
