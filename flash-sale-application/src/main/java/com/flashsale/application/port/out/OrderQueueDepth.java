package com.flashsale.application.port.out;

/** 建單佇列的深度（ADR-0023）——單機記憶體級的閘門，與 {@link SoldOutMarker} 同型。 */
public interface OrderQueueDepth {

    /** 目前積壓的訊息數。 */
    long backlog();

    /** 最近實測的建單速率（每秒）。尚無資料時回 0。 */
    double drainRatePerSecond();

    /** 依目前積壓與速率推估的等待秒數；無法推估時回 {@code -1}。 */
    long estimatedWaitSeconds();

    /** 積壓是否已經超過可接受的等待時間。 */
    boolean isOverloaded();
}
