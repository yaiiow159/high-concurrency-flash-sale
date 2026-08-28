package com.flashsale.application.config;

import java.time.Duration;

/**
 * 對帳策略參數。
 *
 * @param orphanGracePeriod 孤兒扣減的寬限期。庫存已扣但查無訂單，
 *                          只有在超過這段時間後才視為真正的孤兒。
 *                          <b>寬限期必須明顯長於付款期限與 MQ 最大重試時間</b>——
 *                          否則會把「還在佇列裡正常排隊」的請求誤判為孤兒而退庫，
 *                          等訊息真的被消費時就變成超賣
 * @param scanBatchSize     掃描綁定的單批筆數
 * @param autoRepairOrphans 是否自動退回孤兒扣減。<b>預設關閉</b>——
 *                          自動修復的前提是對帳邏輯本身沒有 bug，
 *                          而一個有 bug 的自動修復會比它要修的問題破壞力更大
 */
public record ReconciliationPolicy(
        Duration orphanGracePeriod,
        int scanBatchSize,
        boolean autoRepairOrphans
) {

    public ReconciliationPolicy {
        if (orphanGracePeriod == null || orphanGracePeriod.isNegative() || orphanGracePeriod.isZero()) {
            throw new IllegalArgumentException("orphanGracePeriod 必須為正值");
        }
        if (scanBatchSize <= 0) {
            throw new IllegalArgumentException("scanBatchSize 必須大於 0");
        }
    }

    public static ReconciliationPolicy defaults() {
        return new ReconciliationPolicy(Duration.ofMinutes(30), 500, false);
    }
}
