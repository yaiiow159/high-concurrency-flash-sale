package com.flashsale.application.port.out;

import com.flashsale.domain.activity.SeckillActivity;

import java.util.List;
import java.util.Optional;

/**
 * 活動查詢埠（出站）。
 *
 * <p>應用層只認得這個介面。多級快取（Caffeine → Redis → MySQL）是<b>基礎設施的實作細節</b>，
 * 以 Decorator 疊在資料庫實作之外，應用層完全無感。
 * 這讓「要不要加一層快取」變成一個純粹的部署決策，而非需要改動 Use Case 的重構。
 */
public interface ActivityRepository {

    Optional<SeckillActivity> findById(Long activityId);

    /** 已上架且尚未結束的活動，用於啟動預熱與首頁列表。 */
    List<SeckillActivity> findOnlineActivities();
}
