package com.flashsale.application.port.out;

import com.flashsale.application.port.out.message.SeckillOrderMessage;

/** 建單訊息投遞埠（出站）。 */
public interface SeckillMessagePublisher {

    /** 同步投遞（等待 broker ack）。 */
    void publish(SeckillOrderMessage message);
}
