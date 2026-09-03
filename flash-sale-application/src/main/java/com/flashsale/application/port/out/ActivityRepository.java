package com.flashsale.application.port.out;

import com.flashsale.domain.activity.SeckillActivity;

import java.time.Instant;
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

    /**
     * 更新活動。
     *
     * <p><b>快取失效綁在這個方法上，而不是另開一個 evict 埠。</b>
     * 「寫入時讓快取失效」是快取實作自己的責任；把它抬到埠上，
     * 應用層就得記得每次寫完再呼叫一次——而那種「記得」遲早會有人忘記。
     *
     * <p>先前這個埠是唯讀的，活動狀態只能靠直接改資料庫變更，
     * 於是沒有任何寫入路徑可以掛失效邏輯：緊急下架後最壞 6 分鐘內
     * 請求還是進得來、庫存照樣扣。
     */
    SeckillActivity update(SeckillActivity activity);

    /** 已上架且尚未結束的活動，用於啟動預熱與首頁列表。 */
    List<SeckillActivity> findOnlineActivities();

    /**
     * 需要納入對帳的活動：進行中的，加上剛結束不久的。
     *
     * <p><b>為什麼要含「剛結束」的活動？</b> 庫存洩漏最常發生在活動尾聲——
     * 尖峰過後才進 DLQ 的訊息、活動結束當下逾時的訂單，都是在這個時間點才浮現。
     * 只對帳進行中的活動，等於系統性地漏掉最容易出問題的那一段。
     *
     * @param endedAfter 結束時間晚於此刻的活動仍納入對帳，通常取「現在 - 庫存鍵保留時長」
     */
    List<SeckillActivity> findForReconciliation(Instant endedAfter);

    /**
     * 結束時間早於指定時刻的活動，供庫存釋放使用。
     *
     * <p>與 {@link #findForReconciliation} 的時間方向相反：對帳要的是「還沒完全冷卻」的，
     * 釋放要的是「已經完全冷卻」的。兩者用同一個緩衝期切開，
     * 保證任何一場活動不會同時被兩邊處理。
     *
     * @param endedBefore 通常取「現在 - 庫存鍵保留時長」
     */
    List<SeckillActivity> findEndedBefore(Instant endedBefore);
}
