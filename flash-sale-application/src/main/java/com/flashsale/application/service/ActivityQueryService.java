package com.flashsale.application.service;

import com.flashsale.application.port.in.ActivityQueryUseCase;
import com.flashsale.application.port.in.dto.ActivityView;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 活動查詢服務。 */
@Service
public class ActivityQueryService implements ActivityQueryUseCase {

    private final ActivityRepository activityRepository;
    private final StockRepository stockRepository;
    private final Clock clock;

    public ActivityQueryService(ActivityRepository activityRepository,
                                StockRepository stockRepository,
                                Clock clock) {
        this.activityRepository = activityRepository;
        this.stockRepository = stockRepository;
        this.clock = clock;
    }

    @Override
    public ActivityView findById(Long activityId) {
        SeckillActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND));
        return toView(activity, clock.instant());
    }

    @Override
    /** {@inheritDoc} */
    public List<ActivityView> listAllForAdmin(int page, int size) {
        Instant now = clock.instant();
        return activityRepository.findAllForAdmin(size, page * size).stream()
                .map(activity -> toView(activity, now))
                .toList();
    }

    @Override
    public List<ActivityView> listOnlineActivities() {
        Instant now = clock.instant();
        return activityRepository.findOnlineActivities().stream()
                .map(activity -> toView(activity, now))
                .toList();
    }

    private ActivityView toView(SeckillActivity activity, Instant now) {
        long available = stockRepository.availableStock(activity.id());
        // 未預熱時 Redis 回 -1，對外統一呈現為 0，避免前端出現負數庫存。
        return ActivityView.of(activity, Math.max(available, 0L), now);
    }
}
