package com.flashsale.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 秒殺系統的可調參數。
 *
 * <p>用巢狀 record 而非扁平的一堆欄位，是為了讓設定檔的結構與程式碼的結構一致——
 * {@code flash-sale.mq.send-timeout} 在 yml 與 Java 裡長得一樣，改設定時不用猜對應關係。
 *
 * <p>所有欄位都給了預設值：一個剛 clone 下來的專案應該能直接跑起來，
 * 而不是先解一輪「缺少必要設定」的錯誤。
 */
@ConfigurationProperties(prefix = "flash-sale")
public record FlashSaleProperties(
        @DefaultValue Mq mq,
        @DefaultValue Order order,
        @DefaultValue Stock stock,
        @DefaultValue Snowflake snowflake,
        @DefaultValue Outbox outbox
) {

    /**
     * @param sendTimeout 等待 broker ack 的上限。設短是刻意的——
     *                    秒殺場景寧可快速失敗讓使用者重試，也不要讓請求執行緒被佔住
     */
    public record Mq(@DefaultValue("500ms") Duration sendTimeout) {
    }

    /**
     * @param paymentWindow         付款期限，逾時由排程關單退庫
     * @param compensationBatchSize 關單排程單批處理上限
     */
    public record Order(
            @DefaultValue("15m") Duration paymentWindow,
            @DefaultValue("200") int compensationBatchSize) {
    }

    /**
     * @param keyTtlBuffer 庫存鍵在活動結束後的保留時長，
     *                     讓尚未跑完的補償流程仍有鍵可退
     */
    public record Stock(@DefaultValue("2h") Duration keyTtlBuffer) {
    }

    /**
     * @param nodeId 節點編號（0-1023）。<b>多副本部署時必須各自不同</b>，
     *               否則會產生重複的訂單號。生產環境應由 StatefulSet 序號或環境變數注入
     */
    public record Snowflake(@DefaultValue("0") long nodeId) {
    }

    /**
     * @param batchSize     中繼器單次搬運的事件數
     * @param maxRetry      投遞重試上限，超過轉為 DEAD 等待人工處理
     * @param retentionDays 已投遞紀錄的保留天數，逾期清理以免表無限成長
     */
    public record Outbox(
            @DefaultValue("200") int batchSize,
            @DefaultValue("5") int maxRetry,
            @DefaultValue("7") int retentionDays) {
    }
}
