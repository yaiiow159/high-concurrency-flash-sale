package com.flashsale.application.port.out;

import java.time.Instant;
import java.util.List;

/** 到貨通知訂閱的持久化埠（出站）。 */
public interface RestockSubscriptionRepository {

    /** 訂閱。已經訂過（且還沒通知）就什麼都不做，靠唯一索引擋重複。 */
    void subscribe(Long userId, Long skuId, Instant now);

    void unsubscribe(Long userId, Long skuId);

    /** 這個使用者還在等哪些 SKU。 */
    List<Long> findPendingSkuIds(Long userId);

    long countPending(Long userId);

    /**
     * 有人在等、而且現在真的有貨的 SKU。
     *
     * <p>用輪詢而不是讓庫存服務回呼：補貨有好幾條路徑（退貨、逾時關單、
     * 活動釋放、人工補貨），逐條掛勾會漏，而且會讓庫存反過來依賴通知。
     */
    List<Long> findRestockedSkuIds(int limit);

    /** 某個 SKU 有誰在等，最多取 limit 筆。 */
    List<Pending> findWaitersFor(Long skuId, int limit);

    /** 標記已通知。回傳實際更新的筆數——它同時是「這一輪真的通知了幾個人」。 */
    int markNotified(List<Long> subscriptionIds, Instant now);

    record Pending(Long subscriptionId, Long userId) {
    }
}
