package com.flashsale.infrastructure.adapter.out.cache;

import com.flashsale.application.port.out.SoldOutMarker;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 售罄標記的本機實作——削峰漏斗的第一層。
 *
 * <p>用 Caffeine 而非 {@code ConcurrentHashMap}，是為了拿到 TTL 與容量上限：
 * 沒有 TTL 的標記在庫存被補償退回後會永久擋住請求；沒有容量上限則會隨活動數量無限成長。
 *
 * <p><b>為什麼不用 Redis 做全域標記？</b> 因為那就違背了設計初衷——
 * 這一層存在的意義正是「不要打 Redis」。各節點獨立標記，最壞情況是每個節點各多打一次 Redis，
 * 這個代價遠低於維護跨節點一致性。
 *
 * <p><b>TTL 的取捨</b>：設得太長，補償退回的庫存會有一段時間搶不到（少賣）；
 * 設得太短，售罄後的洪峰會週期性打穿到 Redis。3 秒是兼顧兩者的經驗值，
 * 且補償成功時會主動 {@link #clear} 掉標記，正常情況下不必等它自然過期。
 */
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
