package com.flashsale.infrastructure.adapter.out.mq;

/** Topic 名稱的唯一來源。 */
public final class KafkaTopics {

    /** 建單訊息：削峰的主要載體，流量最大的 topic。 */
    public static final String ORDER_CREATE = "seckill.order.create";

    /** 建單死信佇列：重試耗盡的訊息落腳處。 */
    public static final String ORDER_CREATE_DLT = ORDER_CREATE + ".DLT";

    /** 領域事件：由 Outbox 中繼器投遞，下游依 {@code eventType} 標頭分流。 */
    public static final String ORDER_EVENT = "seckill.order.event";

    /** 領域事件的死信佇列。 */
    public static final String ORDER_EVENT_DLT = ORDER_EVENT + ".DLT";

    /** 事件型別標頭，讓消費端不必反序列化整個 payload 就能路由。 */
    public static final String HEADER_EVENT_TYPE = "eventType";

    private KafkaTopics() {
    }
}
