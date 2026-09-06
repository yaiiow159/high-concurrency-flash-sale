package com.flashsale.api.adapter.in.web;

import com.flashsale.application.service.MediaReconciliationService;
import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.InventoryReconciliationUseCase;
import com.flashsale.application.port.in.StockReconciliationUseCase;
import com.flashsale.application.port.in.MembershipReconciliationUseCase;
import com.flashsale.application.port.in.dto.PointBalanceReconciliation;
import com.flashsale.application.port.in.StockReleaseUseCase;
import com.flashsale.application.port.in.dto.ActivityReconciliation;
import com.flashsale.application.port.in.dto.SkuReconciliation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 庫存維運 API。 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
@Tag(name = "庫存維運", description = "對帳查證與活動庫存釋放")
public class InventoryAdminController {

    private final StockReconciliationUseCase stockReconciliationUseCase;
    private final MediaReconciliationService mediaReconciliationService;
    private final InventoryReconciliationUseCase inventoryReconciliationUseCase;
    private final StockReleaseUseCase stockReleaseUseCase;
    private final MembershipReconciliationUseCase membershipReconciliationUseCase;

    public InventoryAdminController(StockReconciliationUseCase stockReconciliationUseCase,
                                    InventoryReconciliationUseCase inventoryReconciliationUseCase,
                                    StockReleaseUseCase stockReleaseUseCase,
                                    MembershipReconciliationUseCase membershipReconciliationUseCase,
                                    MediaReconciliationService mediaReconciliationService) {
        this.mediaReconciliationService = mediaReconciliationService;
        this.membershipReconciliationUseCase = membershipReconciliationUseCase;
        this.stockReconciliationUseCase = stockReconciliationUseCase;
        this.inventoryReconciliationUseCase = inventoryReconciliationUseCase;
        this.stockReleaseUseCase = stockReleaseUseCase;
    }

    @GetMapping("/reconciliation/activities/{activityId}")
    @Operation(summary = "秒殺庫存對帳", description = "核對 Redis 餘量與訂單數量，只讀不改")
    public ApiResponse<ActivityReconciliation> reconcileActivity(@PathVariable Long activityId) {
        return ApiResponse.ok(stockReconciliationUseCase.reconcile(activityId));
    }

    @GetMapping("/reconciliation/skus/{skuId}")
    @Operation(summary = "一般庫存對帳", description = "核對庫存數字與異動流水，只讀不改")
    public ApiResponse<SkuReconciliation> reconcileSku(@PathVariable Long skuId) {
        return ApiResponse.ok(inventoryReconciliationUseCase.reconcile(skuId));
    }

    @GetMapping("/reconciliation/skus")
    @Operation(summary = "全量一般庫存對帳", description = "只回傳不平的 SKU；帳平的不佔回應")
    public ApiResponse<List<SkuReconciliation>> reconcileAllSkus() {
        return ApiResponse.ok(inventoryReconciliationUseCase.reconcileAll());
    }

    /** 手動觸發活動庫存釋放。 */
    /** 積分對帳：餘額與流水加總不符的帳戶。 */
    @GetMapping("/reconciliation/points")
    @Operation(summary = "積分對帳", description = "只回不平的帳戶；只讀不修")
    public ApiResponse<PointBalanceReconciliation> reconcilePoints() {
        return ApiResponse.ok(membershipReconciliationUseCase.reconcile());
    }

    @PostMapping("/activities/{activityId}/release")
    @Operation(summary = "釋放活動庫存", description = "把未售出的量歸還可售池；已釋放過則不重複執行")
    public ApiResponse<Map<String, Object>> release(@PathVariable Long activityId) {
        boolean released = stockReleaseUseCase.release(activityId);
        return ApiResponse.ok(Map.of("activityId", activityId, "released", released));
    }
    /** 圖片對帳（ADR-0027）。 */
    @GetMapping("/reconciliation/media")
    @Operation(summary = "圖片對帳", description = "只報告，不刪除；孤兒與破圖分開統計")
    public ApiResponse<MediaReconciliationService.MediaReconciliation> reconcileMedia() {
        return ApiResponse.ok(mediaReconciliationService.reconcile());
    }

}
