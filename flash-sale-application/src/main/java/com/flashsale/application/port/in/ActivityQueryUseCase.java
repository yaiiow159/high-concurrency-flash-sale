package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ActivityView;

import java.util.List;

/** 活動查詢入站埠。 */
public interface ActivityQueryUseCase {

    ActivityView findById(Long activityId);

    List<ActivityView> listOnlineActivities();

    /**
     * 後台用：所有活動，含草稿與已下架。
     *
     * <p>與 {@link #listOnlineActivities} 分開而不是加旗標——
     * 兩者的呼叫端與授權要求完全不同：前者公開，後者需要 admin scope。
     * 用同一支加參數的話，那個參數就成了唯一的權限判準，
     * 而參數是呼叫端說了算的。
     */
    List<ActivityView> listAllForAdmin(int page, int size);
}
