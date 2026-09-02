package com.flashsale.application.port.out;

import com.flashsale.domain.fulfillment.ShipmentNo;

/**
 * 出貨單號產生器。
 *
 * <p>與訂單號、付款單號共用同一個 Snowflake 核心——
 * 三者各自持有獨立實例會讓序號從同一個點開始，
 * 同一毫秒內就可能撞號（見 {@code SnowflakeIdGenerator} 的說明）。
 */
public interface ShipmentNoGenerator {

    ShipmentNo next();
}
