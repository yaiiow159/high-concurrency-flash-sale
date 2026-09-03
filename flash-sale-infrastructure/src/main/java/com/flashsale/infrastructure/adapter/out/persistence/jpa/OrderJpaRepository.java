package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.OrderEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 訂單的 Spring Data 介面。 */
public interface OrderJpaRepository extends JpaRepository<OrderEntity, Long> {

    /**
     * 以 {@code @EntityGraph} 一次撈出訂單行。
     *
     * <p>訂單行是 LAZY，單筆查詢若不明確指定就會產生第二次查詢；
     * 而聚合根被載入後就該是完整的——半個聚合根比沒有更危險。
     */
    @EntityGraph(attributePaths = "lines")
    Optional<OrderEntity> findByOrderNo(String orderNo);

    @EntityGraph(attributePaths = "lines")
    Optional<OrderEntity> findByRequestId(String requestId);

    boolean existsByRequestId(String requestId);

    /**
     * 撈取逾期未付款訂單。
     *
     * <p>走 {@code idx_status_created} 複合索引；{@code Limit} 讓 SQL 帶上 LIMIT 子句，
     * 避免尖峰後累積的大量待關訂單一次全撈進記憶體。
     *
     * <p>同樣以 EntityGraph 帶出訂單行——關單要產生退庫事件，而事件需要行的內容。
     */
    @EntityGraph(attributePaths = "lines")
    @Query("""
            select o from OrderEntity o
            where o.status = 'PENDING_PAYMENT' and o.createdAt < :deadline
            order by o.createdAt asc
            """)
    List<OrderEntity> findExpiredPending(@Param("deadline") Instant deadline, Limit limit);

    /**
     * 統計某活動仍被佔用的數量。
     *
     * <p><b>走訂單行而非訂單</b>：一張訂單可能只有部分行來自該活動，
     * 用訂單層級的數量會算錯——而對帳算錯會直接誤判為超賣或洩漏。
     *
     * <p>{@code coalesce} 不可省略：沒有任何訂單時 {@code sum} 回傳 null，
     * 拆箱成 long 會直接 NPE，而「活動剛開始還沒有訂單」正是最常見的情況。
     *
     * <p><b>狀態清單必須與 {@code OrderStatus.holdsStock()} 保持一致。</b>
     * 出貨與完成的訂單同樣佔用庫存——貨已經離開倉庫，那批貨確實不在了。
     * 漏掉它們，對帳會把每一筆正常出貨都誤判成庫存洩漏。
     *
     * <p>這份清單寫死在 JPQL 裡是不得已的（查詢要能下推到資料庫），
     * 因此新增訂單狀態時<b>必須回來檢查這裡</b>——
     * {@code OrderStatusStockHoldingTest} 會比對兩邊是否同步。
     */
    @Query("""
            select coalesce(sum(l.quantity), 0)
            from OrderLineEntity l join l.order o
            where l.sourceActivityId = :activityId
              and o.status in ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'COMPLETED')
            """)
    long sumActiveQuantityByActivity(@Param("activityId") Long activityId);

    /**
     * 某使用者的訂單，新到舊。
     *
     * <p><b>用 EntityGraph 一次帶出訂單行</b>：列表要顯示品項摘要，
     * 逐筆再查一次就是典型的 N+1——20 筆訂單變成 21 次查詢。
     */
    @EntityGraph(attributePaths = "lines")
    List<OrderEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 批次查詢存在的訂單號，供對帳比對孤兒扣減。 */
    @Query("select o.orderNo from OrderEntity o where o.orderNo in :orderNos")
    List<String> findExistingOrderNos(@Param("orderNos") Collection<String> orderNos);
}
