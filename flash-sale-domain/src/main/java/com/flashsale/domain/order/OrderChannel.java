package com.flashsale.domain.order;

/** 下單通道。 */
public enum OrderChannel {

    /** 一般下單：同步、交易一致。 */
    NORMAL,

    /** 秒殺下單：非同步、最終一致。 */
    SECKILL
}
