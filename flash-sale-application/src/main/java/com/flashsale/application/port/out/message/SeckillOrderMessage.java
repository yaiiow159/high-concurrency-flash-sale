package com.flashsale.application.port.out.message;

import com.flashsale.application.port.in.command.SeckillCommand;

import java.time.Instant;

/**
 * 投遞到 MQ 的建單訊息——削峰的載體。
 *
 * <p>刻意<b>不</b>直接投遞領域物件：訊息是跨行程契約，一旦領域模型重構就會破壞
 * 正在佇列中的舊訊息。這個 record 是穩定的資料契約，欄位只增不改。
 *
 * @param orderNo    請求進來時就預先產生，供前端輪詢與消費端冪等
 * @param requestId  端到端冪等鍵，同時是庫存補償的憑據
 */
public record SeckillOrderMessage(
        String orderNo,
        Long activityId,
        Long userId,
        int quantity,
        String requestId,
        Instant requestedAt
) {

    public static SeckillOrderMessage of(String orderNo, SeckillCommand command, Instant requestedAt) {
        return new SeckillOrderMessage(
                orderNo,
                command.activityId(),
                command.userId(),
                command.quantity(),
                command.requestId(),
                requestedAt);
    }

    /**
     * MQ 分區鍵：<b>訂單號</b>。
     *
     * <h2>為什麼不是 activityId</h2>
     *
     * <p>用 activityId 看起來合理——「同一活動的訊息落在同一分區，
     * 方便消費端做批次與有序處理」。但消費端<b>既沒有批次也不依賴順序</b>：
     * {@code OrderCreationService} 一次處理一則、各自開交易，
     * 冪等靠 {@code saveIfAbsent} 與 {@code request_id} 唯一索引，
     * 與訊息先後無關。
     *
     * <p>而代價極大：<b>秒殺的定義就是流量集中在同一場活動上。</b>
     * 用 activityId 當鍵，整場秒殺的訊息全部雜湊到同一個分區，
     * 於是不論開幾個分區、設多少 concurrency，實際只有<b>一個</b>執行緒在建單。
     *
     * <p>實測（50 萬庫存、12 分區、concurrency=6）：78,037 則訊息
     * <b>全部落在 partition 6</b>，其餘 11 個分區是空的，
     * 建單速率只有 38 TPS，而入口每秒接得下 1,448 筆——相差 38 倍。
     *
     * <p>改用訂單號：高基數、均勻散佈，而且同一張訂單的重投仍然落在同一分區
     * （retry 相對自己仍然有序）。這正是分區鍵該有的性質。
     */
    public String partitionKey() {
        return orderNo;
    }
}
