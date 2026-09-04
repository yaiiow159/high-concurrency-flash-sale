package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.CatalogAdminUseCase;
import com.flashsale.application.port.in.ProductSearchUseCase;
import com.flashsale.application.port.in.SearchIndexReconciliationUseCase;
import com.flashsale.application.port.in.dto.SearchIndexReconciliation;
import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.application.port.in.dto.ProductView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.flashsale.api.adapter.in.web.dto.CreateProductRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 商品搜尋（ADR-0012）。
 *
 * <p>搜尋端點是<b>公開</b>的——它不帶身分、不改狀態，
 * 而且是使用者進站的第一個動作。要求登入才能搜尋等於把人擋在門外。
 *
 * <p>索引維護與商品上下架則在 {@code /api/v1/admin/**} 底下，
 * 由 {@code SecurityConfig} 統一要求 {@code seckill:admin} scope。
 */
@RestController
@Tag(name = "搜尋", description = "商品搜尋與索引維護")
public class SearchController {

    /** 後台清單一頁最多幾筆。上限由後端夾住，前端傳什麼都不算數。 */
    private static final int MAX_ADMIN_PAGE_SIZE = 100;

    private final ProductSearchUseCase productSearchUseCase;
    private final CatalogAdminUseCase catalogAdminUseCase;
    private final SearchIndexReconciliationUseCase reconciliationUseCase;

    public SearchController(ProductSearchUseCase productSearchUseCase,
                            CatalogAdminUseCase catalogAdminUseCase,
                            SearchIndexReconciliationUseCase reconciliationUseCase) {
        this.productSearchUseCase = productSearchUseCase;
        this.catalogAdminUseCase = catalogAdminUseCase;
        this.reconciliationUseCase = reconciliationUseCase;
    }

    @GetMapping("/api/v1/search/products")
    @Operation(summary = "搜尋商品",
            description = "搜尋引擎故障時自動降級為資料庫查詢，回應的 degraded 會標記")
    public ApiResponse<ProductSearchResult> search(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String brand,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(productSearchUseCase.search(q, categoryId, brand, page, size));
    }

    @PostMapping("/api/v1/admin/search/reindex")
    @Operation(summary = "重建搜尋索引",
            description = "寫入新版本索引後原子切換 alias；失敗時舊索引仍在服務")
    public ApiResponse<Map<String, Long>> reindex() {
        return ApiResponse.ok(Map.of("indexed", productSearchUseCase.reindex()));
    }

    @GetMapping("/api/v1/admin/search/reconciliation")
    @Operation(summary = "搜尋索引對帳",
            description = "比對索引與資料庫；repair=true 時順手修掉差異")
    public ApiResponse<SearchIndexReconciliation> reconcile(
            @RequestParam(defaultValue = "false") boolean repair) {
        return ApiResponse.ok(reconciliationUseCase.reconcile(repair));
    }

    /**
     * 後台的商品清單。
     *
     * <p>回<b>所有狀態</b>的商品，與前台那支不同——看不到草稿的話，
     * 剛建好的商品就找不到入口去上架它。
     *
     * <p>頁大小的上限夾在<b>後端</b>：後台的資料量比前台大一個數量級，
     * 而它的使用者只有幾個人。他們按一下「載入全部」的成本，
     * 會由當下所有正在下單的使用者一起承擔（ADR-0015 決策 4）。
     */
    @GetMapping("/api/v1/admin/products")
    @Operation(summary = "後台商品清單", description = "含草稿與已下架；頁大小上限 100")
    public ApiResponse<List<ProductView>> listAll(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(catalogAdminUseCase.listAll(status,
                Math.max(page, 0), Math.clamp(size, 1, MAX_ADMIN_PAGE_SIZE)));
    }

    /**
     * 建立商品。
     *
     * <p>回 {@code 201}：一件商品真的被建立了。它是 {@code DRAFT}，
     * 還不會出現在商店或搜尋索引裡——上架是下一個獨立的動作。
     */
    @PostMapping("/api/v1/admin/products")
    @Operation(summary = "建立商品", description = "至少一個規格；建立後為 DRAFT，需另外上架")
    public ResponseEntity<ApiResponse<ProductView>> create(
            @Valid @RequestBody CreateProductRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(catalogAdminUseCase.create(request.toCommand())));
    }

    @PostMapping("/api/v1/admin/products/{productId}/on-shelf")
    @Operation(summary = "商品上架")
    public ApiResponse<ProductView> putOnShelf(@PathVariable Long productId) {
        return ApiResponse.ok(catalogAdminUseCase.putOnShelf(productId));
    }

    @PostMapping("/api/v1/admin/products/{productId}/off-shelf")
    @Operation(summary = "商品下架", description = "從搜尋索引移除；資料庫紀錄保留供歷史訂單追溯")
    public ApiResponse<ProductView> takeOffShelf(@PathVariable Long productId) {
        return ApiResponse.ok(catalogAdminUseCase.takeOffShelf(productId));
    }
}
