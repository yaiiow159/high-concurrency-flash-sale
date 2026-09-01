package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.application.port.out.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * 清除過期的 refresh token。
 *
 * <p>這張表的寫入量是「登入次數 × refresh 頻率」——以 15 分鐘的 access token 計算，
 * 一個活躍使用者一天就會產生近百筆紀錄。不清理的話，
 * {@code token_hash} 的唯一索引會持續膨脹，拖慢每一次續期。
 *
 * <p><b>保留期比 TTL 多一段緩衝</b>：已過期的紀錄仍有鑑識價值——
 * 調查「這個帳號是什麼時候、從哪條輪替鏈被盜用的」需要看到歷史。
 * 立刻刪掉等於把安全事件的線索一併刪掉。
 */
@Component
public class RefreshTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupScheduler.class);

    private static final String LOCK_KEY = "seckill:lock:refresh-token-cleanup";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);
    /** 過期後再留這麼久才刪，供安全事件調查。 */
    private static final Duration FORENSIC_RETENTION = Duration.ofDays(30);

    private final RefreshTokenRepository refreshTokenRepository;
    private final DistributedLock distributedLock;
    private final Clock clock;

    public RefreshTokenCleanupScheduler(RefreshTokenRepository refreshTokenRepository,
                                        DistributedLock distributedLock,
                                        Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.distributedLock = distributedLock;
        this.clock = clock;
    }

    @Scheduled(cron = "${flash-sale.security.token-cleanup-cron:0 15 4 * * *}")
    public void cleanup() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    /**
     * 排程方法絕不可讓例外逸出——Spring 會直接取消該任務的後續排程，
     * 清理從此靜默停擺，直到有人發現這張表大得離譜。
     */
    private void runSafely() {
        try {
            int deleted = refreshTokenRepository.deleteExpiredBefore(
                    clock.instant().minus(FORENSIC_RETENTION));
            if (deleted > 0) {
                log.info("清除過期 refresh token {} 筆", deleted);
            }
        } catch (RuntimeException e) {
            log.error("清除過期 refresh token 失敗，明日將重試", e);
        }
    }
}
