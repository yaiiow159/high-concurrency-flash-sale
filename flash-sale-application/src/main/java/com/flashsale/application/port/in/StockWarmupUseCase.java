package com.flashsale.application.port.in;

/** 庫存預熱入站埠。 */
public interface StockWarmupUseCase {

    /**
     * 預熱單一活動的庫存。
     *
     * @param force {@code true} 時覆寫既有餘量（僅限維運補救使用，正常流程一律 {@code false}，
     * 以免把已賣出的量又加回去）
     * @return 預熱後的可用庫存
     */
    long warmUp(Long activityId, boolean force);

    /** 預熱所有已上架且尚未結束的活動，於應用啟動與排程時呼叫。 */
    int warmUpAllOnline();
}
