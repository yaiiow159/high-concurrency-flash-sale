package com.flashsale.application.port.out;

import com.flashsale.domain.order.OrderNo;

/**
 * 訂單編號產生器埠（出站）。
 *
 * <p>必須是<b>本地產生、無遠端呼叫</b>：秒殺鏈路容不下一次為了取號的網路往返。
 * 預設實作為 Snowflake 變形（時間戳 + 節點 + 序號）。
 */
public interface OrderNoGenerator {

    OrderNo next();
}
