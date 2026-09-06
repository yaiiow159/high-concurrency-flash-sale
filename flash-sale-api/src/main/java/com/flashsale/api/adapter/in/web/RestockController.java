package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.RestockNotificationUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 到貨通知。 */
@RestController
@RequestMapping("/api/v1/restock-alerts")
@Tag(name = "到貨通知", description = "缺貨時訂閱，補貨時通知")
public class RestockController {

    private final RestockNotificationUseCase restockNotification;

    public RestockController(RestockNotificationUseCase restockNotification) {
        this.restockNotification = restockNotification;
    }

    @PostMapping("/{skuId}")
    @Operation(summary = "訂閱到貨通知", description = "重複訂閱不是錯誤")
    public ApiResponse<Void> subscribe(@CurrentUser Long userId, @PathVariable Long skuId) {
        restockNotification.subscribe(userId, skuId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{skuId}")
    @Operation(summary = "取消訂閱")
    public ApiResponse<Void> unsubscribe(@CurrentUser Long userId, @PathVariable Long skuId) {
        restockNotification.unsubscribe(userId, skuId);
        return ApiResponse.ok(null);
    }

    @GetMapping
    @Operation(summary = "我在等哪些商品到貨")
    public ApiResponse<List<Long>> pending(@CurrentUser Long userId) {
        return ApiResponse.ok(restockNotification.pendingSkuIds(userId));
    }
}
