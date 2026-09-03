package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.NotificationEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 通知的 Spring Data 介面。 */
public interface NotificationJpaRepository extends JpaRepository<NotificationEntity, Long> {

    Optional<NotificationEntity> findBySourceEventIdAndChannel(String sourceEventId, String channel);

    /**
     * 寫入一筆通知；來源事件在這個管道已有紀錄時什麼都不做。
     *
     * <p><b>用 upsert 而不是「先查、寫入、接住唯一索引衝突」。</b>
     * 那個寫法有一個安靜的陷阱：{@code saveIfAbsent} 的交易傳播是 REQUIRED，
     * 會加入呼叫端（{@code NotificationDispatchService}）的交易。
     * Hibernate flush 撞到唯一索引後，依 JPA 規範會把交易標記成 rollback-only；
     * 在方法內接住那個例外只擋得住例外往外傳，<b>擋不住那個旗標</b>，
     * 外層 commit 時仍會拋 {@code UnexpectedRollbackException}。
     *
     * <p>而派送是兩個管道一個迴圈：站內信先寫成功、Email 撞到衝突的話，
     * 整個交易回滾，連站內信那筆也沒了。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id = id} 是刻意的空更新——
     * 一次往返、不拋例外、不動任何欄位，因此也不會覆寫既有那筆的內容快照。
     *
     * @return 1 表示真的寫入了；0 表示已存在
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification
                (user_id, channel, type, title, body, reference_no, source_event_id,
                 status, attempt_count, created_at, sent_at, version)
            VALUES
                (:userId, :channel, :type, :title, :body, :referenceNo, :sourceEventId,
                 :status, 0, :createdAt, :sentAt, 0)
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("channel") String channel,
                       @Param("type") String type,
                       @Param("title") String title,
                       @Param("body") String body,
                       @Param("referenceNo") String referenceNo,
                       @Param("sourceEventId") String sourceEventId,
                       @Param("status") String status,
                       @Param("createdAt") Instant createdAt,
                       @Param("sentAt") Instant sentAt);

    List<NotificationEntity> findByUserIdAndChannelOrderByCreatedAtDesc(
            Long userId, String channel, Pageable pageable);

    long countByUserIdAndChannelAndReadAtIsNull(Long userId, String channel);

    /** 未讀的站內信，舊到新——先發生的先標記，順序與使用者的閱讀直覺一致。 */
    List<NotificationEntity> findByUserIdAndChannelAndReadAtIsNullOrderByCreatedAtAsc(
            Long userId, String channel, Limit limit);

    /**
     * 撈取待寄送的通知。
     *
     * <p><b>狀態清單含 {@code FAILED}</b>：寄信失敗最常見的成因是 SMTP 暫時故障，
     * 重試就會成功。只撈 {@code PENDING} 等於讓一次網路抖動永久吞掉一封通知。
     *
     * <p>{@code UNDELIVERABLE} 不在清單裡——那些是信箱不存在之類的永久失敗，
     * 撈進來每一輪都只是白試一次。
     *
     * <p>依 {@code createdAt} 排序：先發生的事先通知。
     * 讓「已送達」比「已出貨」先寄到，使用者會以為順序錯了。
     */
    @Query("""
            select n from NotificationEntity n
            where n.channel = :channel
              and n.status in ('PENDING', 'FAILED')
              and n.attemptCount < :maxAttempts
            order by n.createdAt asc
            """)
    List<NotificationEntity> findAwaitingDelivery(@Param("channel") String channel,
                                                  @Param("maxAttempts") int maxAttempts,
                                                  Limit limit);
}
