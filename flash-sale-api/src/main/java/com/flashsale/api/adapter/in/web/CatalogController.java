package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.in.dto.CategoryView;
import com.flashsale.application.port.in.dto.ProductPage;
import com.flashsale.application.port.in.dto.ProductView;
import com.flashsale.application.port.in.dto.SkuStockView;
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
    @Operation(summary = "商品列表",
            description = "keyset 分頁：帶上一頁回傳的 nextCursor 取下一頁；"
                    + "categoryId 包含該類目的整棵子樹；"
                    + "sort 支援 NEWEST/PRICE_ASC/PRICE_DESC/BEST_SELLING/RATING")
    public ApiResponse<ProductPage> listProducts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(catalogQueryUseCase.listProducts(categoryId, sort, cursor, size));
    }

    @GetMapping("/products/{productId}")
    @SecurityRequirements
    @Operation(summary = "商品詳情", description = "含所有 SKU 與各自的規格與價格")
    public ApiResponse<ProductView> findProduct(@PathVariable Long productId) {
        return ApiResponse.ok(catalogQueryUseCase.findProduct(productId));
    }

    @GetMapping("/skus")
    @SecurityRequirements
    @Operation(summary = "批次查詢 SKU",
            description = "供未登入的本地購物車取得商品名與價格；查不到的 SKU 不會出現在結果裡")
    public ApiResponse<List<CatalogQueryUseCase.SkuLookup>> findSkus(
            @RequestParam List<Long> ids) {
        return ApiResponse.ok(catalogQueryUseCase.findSkus(ids));
    }

    @GetMapping("/stock")
    @Operation(summary = "批次查庫存",
            description = "與商品資料分開請求：商品幾週才改一次、庫存每秒變動數千次。"
                    + "充足時只回有無，低於門檻才給確切數量")
    public ApiResponse<List<SkuStockView>> findStock(@RequestParam List<Long> skuIds) {
        return ApiResponse.ok(catalogQueryUseCase.findStock(skuIds));
    }

    @GetMapping("/categories")
    @SecurityRequirements
    @Operation(summary = "類目樹")
    public ApiResponse<List<CategoryView>> categoryTree() {
        return ApiResponse.ok(catalogQueryUseCase.categoryTree());
    }
}
