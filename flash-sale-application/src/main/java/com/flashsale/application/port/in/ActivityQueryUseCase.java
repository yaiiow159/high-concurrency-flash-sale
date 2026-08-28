package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ActivityView;

import java.util.List;

/** 活動查詢入站埠。 */
public interface ActivityQueryUseCase {

    ActivityView findById(Long activityId);

    List<ActivityView> listOnlineActivities();
}
