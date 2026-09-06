package com.flashsale.application.port.in;

import java.util.List;

/** 到貨通知。 */
public interface RestockNotificationUseCase {

    void subscribe(Long userId, Long skuId);

    void unsubscribe(Long userId, Long skuId);

    /** 我還在等哪些 SKU，供商品頁顯示按鈕狀態。 */
    List<Long> pendingSkuIds(Long userId);

    /**
     * 某個 SKU 補貨了，通知等待的人。
     *
     * @return 這一輪實際通知的人數
     */
    int notifyWaiters(Long skuId);

    /**
     * 掃描所有「有人在等而且現在有貨」的 SKU 並通知。
     *
     * @return 這一輪通知的總人數
     */
    int notifyAllRestocked();
}
