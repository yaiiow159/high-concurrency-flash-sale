package com.flashsale.application.port.out;

import com.flashsale.application.port.out.message.SeckillOrderMessage;

/** 建單訊息投遞埠（出站）。 */
public interface SeckillMessagePublisher {

    /**
     * 投遞建單訊息。
     *
     * <p><b>實作只在「確定沒有送出」時拋例外。</b>等待逾時不算失敗——
     * 生產者仍在自己的 delivery.timeout 內重試，此時回傳 {@link Outcome#PENDING}。
     *
     * <p>這個區分是防超賣的一部分：把逾時當成失敗而去退庫，
     * 而訊息之後才送達的話，庫存已經被別人買走、訂單卻仍會建立（ADR-0030）。
     */
    Outcome publish(SeckillOrderMessage message);

    enum Outcome {

        /** broker 已確認收下。 */
        ACKED,

        /**
         * 等待逾時，生產者仍在重試。
         *
         * <p>語意是「<b>不知道送到沒</b>」，不是「沒送到」——不可據此退庫。
         * 最終真的沒送達時，那筆扣減會被對帳的孤兒偵測撈出來。
         */
        PENDING
    }
}
