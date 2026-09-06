package com.flashsale.application.port.out;

/** 售罄標記埠（出站）——單機記憶體級的快速失敗閘門。 */
public interface SoldOutMarker {

    boolean isSoldOut(Long activityId);

    void markSoldOut(Long activityId);

    /** 庫存補償退回後清除標記，讓退回的量能被重新搶購。 */
    void clear(Long activityId);
}
