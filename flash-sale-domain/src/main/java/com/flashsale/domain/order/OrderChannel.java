package com.flashsale.domain.order;

/**
 * 下單通道。
 *
 * <p>秒殺與一般下單共用同一個聚合根與狀態機，只在「庫存扣減機制」與
 * 「訂單建立路徑」分岔（見 ADR-0006）。
 *
 * <p><b>這個欄位只用於追溯與報表，不可用於控制流程。</b>
 * 一旦領域層出現 {@code if (channel == SECKILL)}，兩條通道的差異就滲透進
 * 共用的部分——那正是雙通道設計要避免的事。差異應該放在建立路徑，
 * 而非讓聚合根自己知道是誰建立了它。
 *
 * <p>ArchUnit 抓不到這種違規，只能靠 review 守住。
 */
public enum OrderChannel {

    /** 一般下單：同步、交易一致。 */
    NORMAL,

    /** 秒殺下單：非同步、最終一致。 */
    SECKILL
}
