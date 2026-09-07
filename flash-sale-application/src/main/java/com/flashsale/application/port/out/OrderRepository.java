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

    /** 建立訂單，若 {@code requestId} 已存在則不寫入。 */
    Optional<Order> saveIfAbsent(Order order);

    /** 更新既有訂單（狀態流轉），以樂觀鎖版本號防止並發覆寫。 */
    Order update(Order order);

    Optional<Order> findByOrderNo(OrderNo orderNo);

    /**
     * 讀寫內部註記。
     *
     * <p><b>刻意不走 {@link Order} 聚合</b>：它是營運的工作筆記，
     * 不是訂單事實的一部分——訂單建立後不可變那條規則不該因為它被打破。
     */
    Optional<String> findStaffNote(OrderNo orderNo);

    void updateStaffNote(OrderNo orderNo, String note);

    /** 取出訂單並鎖住那一列，直到當前交易結束。 */
    Optional<Order> findByOrderNoForUpdate(OrderNo orderNo);

    Optional<Order> findByRequestId(String requestId);

    /** 統計某活動「仍佔用庫存」的訂單總數量，判準見 {@code OrderStatus.holdsStock()}。 */
    long sumActiveQuantity(Long activityId);

    /** 批次查詢哪些訂單號確實存在於資料庫。 */
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
     * 我的訂單。
     *
     * @param status 只看某個狀態；{@code null} 代表全部。
     * <b>在資料庫篩，不是撈回來再過濾</b>——後者會讓
     * 「待付款」這種少數狀態需要翻很多頁才湊得滿一頁
     */
    List<Order> findByUserId(Long userId, String status, int limit, int offset);

    /** 後台搜尋條件。全部可為 null；三個條件是 AND。 */
    record SearchCriteria(String orderNo, Long userId, String status) {
    }

    /** 後台搜尋，建立時間新到舊。 */
    List<Order> search(SearchCriteria criteria, int limit, int offset);

    long countSearch(SearchCriteria criteria);
}
