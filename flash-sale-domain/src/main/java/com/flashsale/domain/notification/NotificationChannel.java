package com.flashsale.domain.notification;

/**
 * 通知管道。
 *
 * <p><b>每個管道是一筆獨立的通知紀錄，不是一筆紀錄上的兩個旗標。</b>
 * Email 可能寄失敗而站內信已經送到——那是兩條各自獨立的生命週期，
 * 塞進同一列就得讓一個狀態欄位同時表達兩件事的進度，
 * 而「寄信失敗要重試、但站內信不要重複寫」這個規則就無處可放。
 */
public enum NotificationChannel {

    /**
     * 站內信。
     *
     * <p><b>寫進資料庫就等於送達</b>，沒有「發送中」這個狀態——
     * 它不經過任何外部系統，交易 commit 之後使用者就看得到。
     */
    IN_APP,

    /**
     * 電子郵件。
     *
     * <p>要經過外部 SMTP，因此有 {@code PENDING → SENT/FAILED} 的過程，
     * 且寄送一律在交易之外（理由與退款打金流相同）。
     */
    EMAIL;

    /** 這個管道需要經過外部系統嗎？決定它建立時是 PENDING 還是 SENT。 */
    public boolean requiresDelivery() {
        return this == EMAIL;
    }
}
