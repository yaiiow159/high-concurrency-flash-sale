package com.flashsale.application.port.out;

import com.flashsale.domain.stock.StockDeductionResult;

import java.time.Duration;

/**
 * 庫存埠（出站）。
 *
 * <p>實作必須保證 {@link #deduct} 的<b>原子性</b>：判重、檢查餘量、檢查限購、扣減、
 * 記錄請求五個動作必須在同一個不可中斷的單元內完成。Redis 實作以 Lua 腳本達成；
 * 任何「先 GET 再 DECR」的實作都會超賣，{@code NoOversellConcurrencyTest} 會抓出來。
 */
public interface StockRepository {

    /**
     * 原子扣減庫存並將本次扣減綁定到指定訂單號。
     *
     * @param requestId 端到端冪等鍵；重送相同 requestId 不會二次扣減，
     *                  且會回傳首次扣減時綁定的訂單號
     * @param orderNo   本次請求預先產生的訂單號
     * @return 扣減結果。不拋例外——由呼叫端決定如何映射為業務語意
     */
    StockDeductionResult deduct(Long activityId, Long userId, int quantity,
                               int perUserLimit, String requestId, String orderNo);

    /**
     * 補償：把先前扣減的庫存退回（Saga 補償動作）。
     *
     * <p>必須冪等——以 {@code requestId} 判斷是否真的扣過，重複呼叫只會退一次。
     * 補償排程與 DLQ 消費端都可能對同一筆訂單發起補償，這裡的冪等是最後保險。
     *
     * @return {@code true} 表示本次確實退回了庫存；{@code false} 表示先前未扣減或已退過
     */
    boolean restore(Long activityId, Long userId, int quantity, String requestId);

    /**
     * 初始化庫存。
     *
     * @param force {@code false} 時僅在鍵不存在才寫入，避免把已賣出的量又加回去
     */
    void initialize(Long activityId, int totalStock, Duration ttl, boolean force);

    /** 目前可用餘量；活動未預熱時回傳 {@code -1}（與「餘量為 0」明確區分）。 */
    long availableStock(Long activityId);
}
