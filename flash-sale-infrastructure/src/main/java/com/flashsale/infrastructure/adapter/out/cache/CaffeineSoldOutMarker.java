package com.flashsale.infrastructure.adapter.out.cache;

import com.flashsale.application.port.out.SoldOutMarker;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 售罄標記的本機實作——削峰漏斗的第一層。 */
@Component
public class CaffeineSoldOutMarker implements SoldOutMarker {

    private static final Logger log = LoggerFactory.getLogger(CaffeineSoldOutMarker.class);
    private static final Duration MARKER_TTL = Duration.ofSeconds(3);

    private final Cache<Long, Boolean> markers = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(MARKER_TTL)
            .build();

    @Override
    public boolean isSoldOut(Long activityId) {
        return markers.getIfPresent(activityId) != null;
    }

    @Override
    public void markSoldOut(Long activityId) {
        markers.put(activityId, Boolean.TRUE);
    }

    @Override
    public void clear(Long activityId) {
        if (markers.asMap().remove(activityId) != null) {
            log.info("活動 {} 有庫存回補，撤下本機售罄標記", activityId);
        }
    }
}
