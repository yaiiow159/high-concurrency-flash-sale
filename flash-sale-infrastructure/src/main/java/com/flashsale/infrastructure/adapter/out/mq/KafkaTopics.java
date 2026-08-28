package com.flashsale.infrastructure.adapter.out.mq;

/**
 * Topic 名稱的唯一來源。
 *
 * <p>Topic 名稱寫死在 {@code @KafkaListener} 註解的字串裡是常見的坑——
 * 生產端改了名稱，消費端卻毫無感覺地繼續聽著空 topic，且編譯完全通過。
 * 集中成常數後，至少改名時 IDE 會告訴你有哪些地方在用。
 */
public final class KafkaTopics {

    /** 建單訊息：削峰的主要載體，流量最大的 topic。 */
    public static final String ORDER_CREATE = "seckill.order.create";

    /**
     * 建單死信佇列：重試耗盡的訊息落腳處。
     *
     * <p>後綴 {@code .DLT} 是 Spring Kafka {@code DeadLetterPublishingRecoverer} 的預設慣例，
     * 沿用它可省下自訂命名解析器，也讓維運看到名稱就知道用途。
     */
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
