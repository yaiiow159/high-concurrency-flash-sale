package com.flashsale.application.port.out;

import com.flashsale.domain.activity.SeckillActivity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 活動查詢埠（出站）。 */
public interface ActivityRepository {

    Optional<SeckillActivity> findById(Long activityId);

    /** 更新活動。 */
    SeckillActivity update(SeckillActivity activity);

    /** 已上架且尚未結束的活動，用於啟動預熱與首頁列表。 */
    List<SeckillActivity> findOnlineActivities();

    /** 後台用：所有活動（含草稿與已下架），由新到舊。 */
    List<SeckillActivity> findAllForAdmin(int limit, int offset);

    /** 需要納入對帳的活動：進行中的，加上剛結束不久的。 */
    List<SeckillActivity> findForReconciliation(Instant endedAfter);

    /** 結束時間早於指定時刻的活動，供庫存釋放使用。 */
    List<SeckillActivity> findEndedBefore(Instant endedBefore);
}
