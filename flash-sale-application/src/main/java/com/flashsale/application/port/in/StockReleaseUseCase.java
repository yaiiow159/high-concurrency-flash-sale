package com.flashsale.application.port.in;

/**
 * 活動結束後歸還未售出庫存。
 *
 * <p>與預熱（劃撥）成對：切出去的量最終都要收回來，
 * 否則每辦一場活動就有一批貨永遠卡在劃撥狀態。
 */
public interface StockReleaseUseCase {

    /** 掃描所有已過緩衝期的結束活動並釋放，回傳實際釋放的場數。 */
    int releaseEndedActivities();

    /**
     * 釋放單一活動。
     *
     * @return {@code true} 表示本次確實釋放了；已釋放過或無劃撥紀錄時回 {@code false}
     */
    boolean release(Long activityId);
}
