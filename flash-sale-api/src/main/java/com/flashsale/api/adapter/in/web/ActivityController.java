package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.ActivityQueryUseCase;
import com.flashsale.application.port.in.StockWarmupUseCase;
import com.flashsale.application.port.in.dto.ActivityView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 活動查詢與維運操作 API。 */
@RestController
@RequestMapping("/api/v1/activities")
@Tag(name = "活動", description = "活動查詢與庫存預熱")
public class ActivityController {

    private final ActivityQueryUseCase activityQueryUseCase;
    private final StockWarmupUseCase stockWarmupUseCase;

    public ActivityController(ActivityQueryUseCase activityQueryUseCase,
                              StockWarmupUseCase stockWarmupUseCase) {
        this.activityQueryUseCase = activityQueryUseCase;
        this.stockWarmupUseCase = stockWarmupUseCase;
    }

    @GetMapping
    @Operation(summary = "已上架活動列表")
    public ApiResponse<List<ActivityView>> listOnline() {
        return ApiResponse.ok(activityQueryUseCase.listOnlineActivities());
    }

    @GetMapping("/{activityId}")
    @Operation(summary = "活動詳情", description = "庫存餘量取自 Redis 即時值，不做快取")
    public ApiResponse<ActivityView> findById(@PathVariable Long activityId) {
        return ApiResponse.ok(activityQueryUseCase.findById(activityId));
    }

    /**
     * 手動觸發庫存預熱。
     *
     * <p><b>{@code force=true} 會直接覆寫 Redis 餘量，把已賣出的量抹掉。</b>
     * 這是維運補救用的最後手段，正式環境應以權限控制鎖死；
     * 此處保持開放僅為方便本地驗證與壓測。
     */
    @PostMapping("/{activityId}/warm-up")
    @Operation(summary = "手動預熱庫存", description = "force=true 會覆寫既有餘量，僅限維運補救")
    public ApiResponse<Map<String, Object>> warmUp(
            @PathVariable Long activityId,
            @RequestParam(defaultValue = "false") boolean force) {

        long available = stockWarmupUseCase.warmUp(activityId, force);
        return ApiResponse.ok(Map.of("activityId", activityId, "availableStock", available));
    }
}
