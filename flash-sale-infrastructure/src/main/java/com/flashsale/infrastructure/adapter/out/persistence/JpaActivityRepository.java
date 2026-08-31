package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.SeckillActivityJpaRepository;
import com.flashsale.infrastructure.adapter.out.persistence.mapper.ActivityMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 活動查詢埠的資料庫實作——多級快取的最終回源目標。
 *
 * <p>Bean 名稱刻意固定為 {@code jpaActivityRepository}，讓
 * {@code MultiLevelActivityRepository} 能以 {@code @Qualifier} 精準注入它作為 delegate，
 * 而不會不小心注入到自己造成無限遞迴。
 */
@Repository("jpaActivityRepository")
public class JpaActivityRepository implements ActivityRepository {

    private final SeckillActivityJpaRepository jpaRepository;
    private final Clock clock;

    public JpaActivityRepository(SeckillActivityJpaRepository jpaRepository, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SeckillActivity> findById(Long activityId) {
        return jpaRepository.findById(activityId).map(ActivityMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SeckillActivity> findOnlineActivities() {
        return jpaRepository.findOnline(clock.instant()).stream()
                .map(ActivityMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SeckillActivity> findForReconciliation(Instant endedAfter) {
        return jpaRepository.findForReconciliation(endedAfter).stream()
                .map(ActivityMapper::toDomain)
                .toList();
    }
}
