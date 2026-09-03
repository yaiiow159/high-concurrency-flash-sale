package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ActivityView;

/**
 * 活動上下架。
 *
 * <p>存在的理由是<b>快取失效</b>。先前活動狀態只能靠直接改資料庫變更，
 * 而那條路沒有任何地方可以掛失效邏輯——緊急下架一個有問題的活動之後，
 * 最壞 6 分鐘內請求還是進得來、庫存照樣扣，
 * 而營運看不出為什麼「按了沒反應」。
 *
 * <p>走這個端點下架，其他節點最慢 5 秒（L1 TTL）就會看到新狀態。
 */
public interface ActivityAdminUseCase {

    /** 上架。 */
    ActivityView publish(Long activityId);

    /**
     * 下架。
     *
     * <p>只擋住新的搶購，<b>不會動已經扣掉的庫存</b>：
     * 已成立的訂單照常付款出貨。把「下架」與「收回庫存」綁在一起，
     * 緊急下架就會變成一個沒有人敢按的按鈕。
     */
    ActivityView takeOffline(Long activityId);
}
