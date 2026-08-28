package com.flashsale.application.port.out;

import com.flashsale.application.port.out.message.SeckillOrderMessage;

/**
 * 建單訊息投遞埠（出站）。
 *
 * <p>投遞失敗時<b>必須拋例外</b>，讓上游能觸發庫存回滾——
 * 若這裡吞掉例外，庫存已扣但訂單永遠不會建立，就是活生生的少賣。
 */
public interface SeckillMessagePublisher {

    /**
     * 同步投遞（等待 broker ack）。
     *
     * <p>秒殺鏈路上唯一保留同步等待的遠端呼叫：需要確認訊息真的落到 broker，
     * 才敢回覆使用者「已受理」。逾時上限由設定 {@code flash-sale.mq.send-timeout} 控制。
     *
     * @throws com.flashsale.domain.shared.BusinessException 投遞失敗或逾時
     */
    void publish(SeckillOrderMessage message);
}
