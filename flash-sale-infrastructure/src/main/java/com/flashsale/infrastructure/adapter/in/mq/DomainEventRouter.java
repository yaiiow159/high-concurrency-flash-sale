package com.flashsale.infrastructure.adapter.in.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 事件分派：型別相符才反序列化並交給處理器，否則直接忽略。
 *
 * <h2>為什麼值得抽出來</h2>
 *
 * <p>同一個 topic 承載多種事件，因此每個消費端開頭都是
 * {@code if (!X.TYPE.equals(eventType)) return;}。這段在六個消費端各寫一次，
 * 而<b>漏寫它的後果不是拋例外，是安靜地處理錯的事件</b>：
 * Jackson 反序列化到不相符的類別時，缺少的欄位變成 null 而不是報錯，
 * 於是消費端會拿著一個半空的物件繼續跑下去。
 *
 * <p>抽出來之後，「這個消費端關心哪一種事件」變成一個<b>必填的參數</b>，
 * 想漏都漏不掉——這比省下四行重要得多。
 *
 * <p><b>刻意不做成抽象基底類別。</b> {@code @KafkaListener} 的
 * groupId 與 concurrency 每個消費端都不同，繼承會逼出一堆
 * 只為了填參數的抽象方法；而且 Spring 對繼承而來的註解方法
 * 有額外的掃描規則，那是一個不必要的知識負擔。
 */
@Component
public class DomainEventRouter {

    private final ObjectMapper objectMapper;

    public DomainEventRouter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 事件型別相符時反序列化並執行 {@code handler}。
     *
     * <p>不相符時什麼也不做——呼叫端會正常 ack。
     * <b>不記日誌</b>：{@code order.created} 在尖峰時每秒上萬筆，
     * 為每一筆不相關的事件記一行就是一場日誌洪水。
     *
     * @return 有沒有真的處理。呼叫端多半用不到，但它讓
     *         「這則訊息被略過了」在測試裡是可以斷言的
     */
    public <T> boolean route(String payload, String eventType, String expectedType,
                             Class<T> target, ThrowingHandler<T> handler) throws Exception {
        if (!expectedType.equals(eventType)) {
            return false;
        }
        handler.handle(objectMapper.readValue(payload, target));
        return true;
    }

    /**
     * 允許拋出受檢例外的處理器。
     *
     * <p>用它而不是 {@link java.util.function.Consumer}：消費端裡的操作
     * （資料庫、外部服務）本來就會拋受檢例外，而把它們包成
     * {@code RuntimeException} 會讓 Kafka 的錯誤處理器
     * 看不出原本的型別——{@code DefaultErrorHandler} 的
     * 不可重試清單是<b>按例外型別</b>比對的。
     */
    @FunctionalInterface
    public interface ThrowingHandler<T> {
        void handle(T event) throws Exception;
    }
}
