package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.ActivityAdminUseCase;
import com.flashsale.application.port.in.dto.ActivityView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活動上下架。
 *
 * <p>與 {@code ActivityController} 分開而不是加在它下面，理由與履約、退貨相同：
 * 那一支的路徑是公開查詢用的，這一支整段掛在 {@code /api/v1/admin/**} 底下，
 * 由 {@code SecurityConfig} 統一要求 {@code seckill:admin} scope。
 * 混在同一個 controller 裡靠方法上的註解區分權限，少一個就是漏洞。
 *
 * <p><b>這兩個端點存在的理由是快取失效。</b>
 * 先前活動狀態只能靠直接改資料庫變更，而那條路沒有地方可以掛失效邏輯——
 * 緊急下架之後最壞 6 分鐘內請求還是進得來、庫存照樣扣。
 */
@RestController
@RequestMapping("/api/v1/admin/activities")
@Tag(name = "活動維運", description = "上下架")
public class ActivityAdminController {

    private final ActivityAdminUseCase activityAdminUseCase;

    public ActivityAdminController(ActivityAdminUseCase activityAdminUseCase) {
        this.activityAdminUseCase = activityAdminUseCase;
    }

    @PostMapping("/{activityId}/offline")
    @Operation(summary = "下架活動",
            description = "立刻擋住新的搶購；已成立的訂單與已扣的庫存都不受影響")
    public ApiResponse<ActivityView> takeOffline(@PathVariable Long activityId) {
        return ApiResponse.ok(activityAdminUseCase.takeOffline(activityId));
    }

    @PostMapping("/{activityId}/online")
    @Operation(summary = "上架活動")
    public ApiResponse<ActivityView> publish(@PathVariable Long activityId) {
        return ApiResponse.ok(activityAdminUseCase.publish(activityId));
    }
}
