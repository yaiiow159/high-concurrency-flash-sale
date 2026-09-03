package com.flashsale.application.service;

import com.flashsale.application.port.in.ActivityAdminUseCase;
import com.flashsale.application.port.in.dto.ActivityView;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 活動上下架。
 *
 * <p>這一層很薄，狀態轉移的規則在 {@code ActivityStatus} 的轉移表裡。
 * 它真正的職責是<b>讓寫入經過會清快取的那個實作</b>——
 * 注入的是 {@code ActivityRepository} 這個埠，而 Spring 綁上來的是
 * 多級快取裝飾器，於是失效自動發生，這裡不必記得做什麼。
 */
@Service
public class ActivityAdminService implements ActivityAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(ActivityAdminService.class);

    private final ActivityRepository activityRepository;
    private final Clock clock;

    public ActivityAdminService(ActivityRepository activityRepository, Clock clock) {
        this.activityRepository = activityRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ActivityView publish(Long activityId) {
        SeckillActivity activity = require(activityId);
        SeckillActivity published = activityRepository.update(activity.publish());
        log.info("活動 {} 已上架", activityId);
        return ActivityView.of(published, published.totalStock(), clock.instant());
    }

    @Override
    @Transactional
    public ActivityView takeOffline(Long activityId) {
        SeckillActivity activity = require(activityId);
        SeckillActivity offline = activityRepository.update(activity.takeOffline());
        // 下架通常發生在出事的時候，記 info 讓事後回溯查得到是誰、什麼時候按的
        log.info("活動 {} 已下架，新的搶購請求將被拒絕", activityId);
        return ActivityView.of(offline, offline.totalStock(), clock.instant());
    }

    private SeckillActivity require(Long activityId) {
        return activityRepository.findById(activityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND,
                        "活動不存在: " + activityId));
    }
}
