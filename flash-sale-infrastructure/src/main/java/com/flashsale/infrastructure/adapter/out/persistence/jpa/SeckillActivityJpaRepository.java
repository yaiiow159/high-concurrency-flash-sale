package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.SeckillActivityEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** 活動的 Spring Data 介面；僅供基礎設施層內部使用，不外洩到應用層。 */
public interface SeckillActivityJpaRepository extends JpaRepository<SeckillActivityEntity, Long> {

    @Query("""
            select a from SeckillActivityEntity a
            where a.status = 'ONLINE' and a.endAt > :now
            order by a.startAt asc
            """)
    List<SeckillActivityEntity> findOnline(@Param("now") Instant now);

    /**
     * 後台用：所有活動，含草稿與已下架。
     *
     * <p>與 {@link #findOnline} 分開而不是加旗標：兩者的呼叫端完全不同，
     * 而一個布林參數會讓「誰看得到草稿」變成呼叫端的自由心證。
     *
     * <p>由新到舊——後台關心的永遠是最近建的那幾檔。
     */
    @Query("select a from SeckillActivityEntity a order by a.id desc")
    List<SeckillActivityEntity> findAllForAdmin(Pageable pageable);

    /**
     * 需要對帳的活動：已上架，且結束時間仍在保留窗口內。
     *
     * <p>刻意涵蓋剛結束的活動——庫存洩漏最常在活動尾聲才浮現。
     */
    @Query("""
            select a from SeckillActivityEntity a
            where a.status = 'ONLINE' and a.endAt > :endedAfter
            order by a.id asc
            """)
    List<SeckillActivityEntity> findForReconciliation(@Param("endedAfter") Instant endedAfter);

    /**
     * 已完全冷卻、可以釋放庫存的活動。
     *
     * <p>時間方向與 {@link #findForReconciliation} 相反，兩者用同一個緩衝期切開，
     * 保證同一場活動不會同時被對帳與釋放處理。
     *
     * <p>不限定 {@code status}：下架的活動同樣需要把劃撥出去的庫存收回來，
     * 否則營運只要把活動下架，那批貨就永遠卡住了。
     */
    @Query("""
            select a from SeckillActivityEntity a
            where a.endAt <= :endedBefore
            order by a.id asc
            """)
    List<SeckillActivityEntity> findEndedBefore(@Param("endedBefore") Instant endedBefore);
}
