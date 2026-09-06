package com.flashsale.application.port.out;

import com.flashsale.domain.stock.StockBinding;
import com.flashsale.domain.stock.StockDeductionResult;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** 庫存埠（出站）。 */
public interface StockRepository {

    /**
     * 原子扣減庫存並將本次扣減綁定到指定訂單號。
     *
     * @param requestId 端到端冪等鍵；重送相同 requestId 不會二次扣減，
     * 且會回傳首次扣減時綁定的訂單號
     * @param orderNo   本次請求預先產生的訂單號
     * @return 扣減結果。不拋例外——由呼叫端決定如何映射為業務語意
     */
    StockDeductionResult deduct(Long activityId, Long userId, int quantity,
                               int perUserLimit, String requestId, String orderNo);

    /** 補償：把先前扣減的庫存退回（Saga 補償動作）。 */
    boolean restore(Long activityId, Long userId, int quantity, String requestId);

    /**
     * 初始化庫存。
     *
     * @param force {@code false} 時僅在鍵不存在才寫入，避免把已賣出的量又加回去
     */
    void initialize(Long activityId, int totalStock, Duration ttl, boolean force);

    /** 目前可用餘量；活動未預熱時回傳 {@code -1}（與「餘量為 0」明確區分）。 */
    long availableStock(Long activityId);

    /** 丟棄此活動的所有庫存鍵（餘量、限購計數、扣減憑證）。 */
    void discard(Long activityId);

    /** 分批掃描此活動所有「已扣減庫存」的請求綁定，供對帳找出孤兒扣減。 */
    void scanBindings(Long activityId, int batchSize, Consumer<List<StockBinding>> batchConsumer);
}
