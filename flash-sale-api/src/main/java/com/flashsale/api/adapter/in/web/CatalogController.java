package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.in.dto.CategoryView;
import com.flashsale.application.port.in.dto.ProductView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品目錄 API。
 *
 * <p>全部開放匿名——商品頁不該逼使用者先登入才能看。
 * 這也是這些端點能被 CDN 快取的前提：帶 {@code Authorization} 的請求無法共用快取。
 *
 * <p>回應<b>不含庫存</b>：庫存變動極快，混進商品資料會讓整個商品頁失去快取價值。
 * 前端另外請求庫存（與秒殺頁同一個手法）。
 */
@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "商品目錄", description = "類目、商品與 SKU 查詢")
public class CatalogController {

    private final CatalogQueryUseCase catalogQueryUseCase;

    public CatalogController(CatalogQueryUseCase catalogQueryUseCase) {
        this.catalogQueryUseCase = catalogQueryUseCase;
    }

    @GetMapping("/products")
    @SecurityRequirements
    @Operation(summary = "商品列表", description = "回應為精簡版，不含描述與完整 SKU 清單")
    public ApiResponse<List<ProductView>> listProducts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // 分頁上限由 Use Case 夾住——這是對外開放的端點，
        // 沒有上限的話任何人都能用 size=1000000 讓資料庫掃全表
        return ApiResponse.ok(catalogQueryUseCase.listProducts(categoryId, page, size));
    }

    @GetMapping("/products/{productId}")
    @SecurityRequirements
    @Operation(summary = "商品詳情", description = "含所有 SKU 與各自的規格與價格")
    public ApiResponse<ProductView> findProduct(@PathVariable Long productId) {
        return ApiResponse.ok(catalogQueryUseCase.findProduct(productId));
    }

    @GetMapping("/categories")
    @SecurityRequirements
    @Operation(summary = "類目樹")
    public ApiResponse<List<CategoryView>> categoryTree() {
        return ApiResponse.ok(catalogQueryUseCase.categoryTree());
    }
}
