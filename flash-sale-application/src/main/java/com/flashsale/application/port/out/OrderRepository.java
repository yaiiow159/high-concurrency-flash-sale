package com.flashsale.application.port.out;

import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.SeckillOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 訂單持久化埠（出站）。 */
public interface OrderRepository {

    /**
     * 建立訂單，若 {@code requestId} 已存在則不寫入。
     *
     * <p>實作端必須在 {@code request_id} 上建立唯一索引，並把唯一鍵衝突轉譯為
     * {@code Optional.empty()}——<b>不要</b>把框架的 {@code DataIntegrityViolationException}
     * 洩漏到應用層。
     *
     * <p>這是防重複下單的<b>最後一道防線</b>：即使 MQ 重複投遞、Redis 冪等鍵過期，
     * 資料庫仍會擋下第二張訂單。分散式系統中每一層都可能失效，最終一致要靠 DB 兜底。
     *
     * @return 新建立的訂單；若該 {@code requestId} 已有訂單則回傳 {@code Optional.empty()}
     */
    Optional<SeckillOrder> saveIfAbsent(SeckillOrder order);

    /** 更新既有訂單（狀態流轉），以樂觀鎖版本號防止並發覆寫。 */
    SeckillOrder update(SeckillOrder order);

    Optional<SeckillOrder> findByOrderNo(OrderNo orderNo);

    Optional<SeckillOrder> findByRequestId(String requestId);

    /**
     * 撈出逾期未付款的訂單，供補償排程批次關單。
     *
     * @param deadline 建立時間早於此刻的待付款訂單即視為逾期
     * @param limit    單批上限，避免一次撈爆記憶體並拉長交易時間
     */
    List<SeckillOrder> findExpiredPendingOrders(Instant deadline, int limit);
}
