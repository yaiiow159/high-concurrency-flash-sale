package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ActivityView;

/** 活動上下架。 */
public interface ActivityAdminUseCase {

    /** 上架。 */
    ActivityView publish(Long activityId);

    /** 下架。 */
    ActivityView takeOffline(Long activityId);
}
