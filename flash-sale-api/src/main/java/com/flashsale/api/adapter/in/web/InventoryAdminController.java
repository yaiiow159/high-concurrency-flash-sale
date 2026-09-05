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

/**
 * 庫存維運 API。
 *
 * <p>全部需要 {@code seckill:admin} scope（由 {@code SecurityConfig} 統一設定）。
 * 這些端點會讀出完整的庫存帳務、也能觸發實際改動庫存的釋放，
 * 不是一般使用者該碰的東西。
 *
 * <p><b>對帳端點只讀不改。</b>觸發對帳不會修復任何偏差——
 * 修復的判斷需要人看過現場才能下，這在 ADR-0008 與對帳服務的註解裡都有說明。
 */
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

    /**
     * 手動觸發活動庫存釋放。
     *
     * <p>排程每 30 分鐘跑一輪，這個端點是給「活動剛結束就想把貨放回去賣」的情況。
     * <b>不會繞過緩衝期的保護</b>：釋放本身仍以劃撥流水判斷是否已執行過，
     * 且 Redis 餘量是當下讀的——提早呼叫只會把還可能被補償退回的量算漏。
     */
    /**
     * 積分對帳：餘額與流水加總不符的帳戶。
     *
     * <p><b>只讀不修</b>——與一般庫存對帳同一個立場：這裡的偏差本身就代表
     * 有東西繞過了正規路徑，此時「自動修正」等於用一個猜測覆蓋另一個猜測。
     * 而且兩個方向都可能是對的：餘額多了可能是流水漏寫，也可能是有人直接改了餘額。
     */
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
    /**
     * 圖片對帳（ADR-0027）。
     *
     * <p><b>沒有自動修復的參數</b>，這與其他對帳不同——
     * 「沒有人指向這個物件」的判斷一旦有 bug，代價是永久性資料遺失，
     * 而那沒有補償路徑。庫存算錯還能退回來，圖片刪掉就沒了。
     */
    @GetMapping("/reconciliation/media")
    @Operation(summary = "圖片對帳", description = "只報告，不刪除；孤兒與破圖分開統計")
    public ApiResponse<MediaReconciliationService.MediaReconciliation> reconcileMedia() {
        return ApiResponse.ok(mediaReconciliationService.reconcile());
    }

}
