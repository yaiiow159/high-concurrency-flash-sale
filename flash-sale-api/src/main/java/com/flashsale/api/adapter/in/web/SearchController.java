package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.CatalogAdminUseCase;
import com.flashsale.application.port.in.ProductSearchUseCase;
import com.flashsale.application.port.in.dto.ProductSearchResult;
import com.flashsale.application.port.in.dto.ProductView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    private final ProductSearchUseCase productSearchUseCase;
    private final CatalogAdminUseCase catalogAdminUseCase;

    public SearchController(ProductSearchUseCase productSearchUseCase,
                            CatalogAdminUseCase catalogAdminUseCase) {
        this.productSearchUseCase = productSearchUseCase;
        this.catalogAdminUseCase = catalogAdminUseCase;
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
