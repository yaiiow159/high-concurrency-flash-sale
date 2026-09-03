package com.flashsale.application.port.out;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderNo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    Optional<Order> saveIfAbsent(Order order);

    /** 更新既有訂單（狀態流轉），以樂觀鎖版本號防止並發覆寫。 */
    Order update(Order order);

    Optional<Order> findByOrderNo(OrderNo orderNo);

    /**
     * 取出訂單並鎖住那一列，直到當前交易結束。
     *
     * <p><b>用途是把「可退數量」的計算序列化。</b>那個計算是
     * 讀既有退貨單 → 檢查餘額 → 寫新退貨單，三步之間沒有任何約束——
     * 兩個併發請求都會讀到同一份舊資料、都通過檢查，於是同一批商品被退兩次。
     * 資料庫層擋不住這件事：一張訂單本來就能有多張退貨單，
     * 所以 return_request 上沒有、也不該有 order_no 的唯一鍵。
     *
     * <p>鎖的是<b>訂單那一列</b>，不同訂單之間完全不競爭。
     * 這與 ADR-0003「不要用鎖包住庫存扣減」不衝突：那條講的是秒殺熱路徑，
     * 所有請求搶同一行、加鎖會把並行度壓成 1；退貨是一天幾百筆的冷路徑，
     * 而且臨界區裡的每一步都是資料庫操作，沒有遠端呼叫會把鎖撐住。
     */
    Optional<Order> findByOrderNoForUpdate(OrderNo orderNo);

    Optional<Order> findByRequestId(String requestId);

    /**
     * 統計某活動「仍佔用庫存」的訂單總數量，判準見 {@code OrderStatus.holdsStock()}。
     *
     * <p>對帳恆等式的右半邊：{@code Redis 餘量 + 本方法回傳值 = 活動總庫存}。
     *
     * <p>多品項後要走 {@code order_line} 的 {@code source_activity_id}——
     * 一張訂單可能只有部分行來自該活動，用訂單層級的數量會算錯。
     */
    long sumActiveQuantity(Long activityId);

    /**
     * 批次查詢哪些訂單號確實存在於資料庫。
     *
     * <p>刻意設計成批次而非逐筆 {@code exists}：對帳要掃描的綁定可能有數十萬筆，
     * 逐筆查詢就是數十萬次往返。
     */
    Set<String> findExistingOrderNos(Collection<String> orderNos);

    /**
     * 撈出逾期未付款的訂單，供補償排程批次關單。
     *
     * @param deadline 建立時間早於此刻的待付款訂單即視為逾期
     * @param limit    單批上限，避免一次撈爆記憶體並拉長交易時間
     */
    List<Order> findExpiredPendingOrders(Instant deadline, int limit);

    /**
     * 某使用者的訂單，新到舊。
     *
     * <p>走 {@code idx_user_created (user_id, created_at)}——
     * 這個索引在 V5 建立訂單表時就一起建了，順序也正好符合
     * 「先用 user_id 過濾、再依時間排序」的查詢形狀，不需要額外的索引。
     */
    List<Order> findByUserId(Long userId, int limit, int offset);
}
