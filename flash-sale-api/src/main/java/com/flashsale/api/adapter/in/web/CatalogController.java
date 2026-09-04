package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.in.dto.CategoryView;
import com.flashsale.application.port.in.dto.ProductPage;
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
    @Operation(summary = "商品列表",
            description = "keyset 分頁：帶上一頁回傳的 nextCursor 取下一頁；"
                    + "categoryId 包含該類目的整棵子樹")
    public ApiResponse<ProductPage> listProducts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(catalogQueryUseCase.listProducts(categoryId, parseCursor(cursor), size));
    }

    /**
     * 解析游標。
     *
     * <p><b>解析不了就當第一頁，不回 400。</b> 游標會出現在網址上，
     * 而使用者會改它、也會貼舊連結——逛商品列表這件事
     * 不該因為網址被改壞而失敗。
     *
     * <p>指向已下架商品的游標仍然是合法的：{@code id < 那個值} 依然成立，
     * 只是那一筆不會出現在結果裡。這是對的行為，不需要特別處理。
     */
    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    @GetMapping("/categories")
    @SecurityRequirements
    @Operation(summary = "類目樹")
    public ApiResponse<List<CategoryView>> categoryTree() {
        return ApiResponse.ok(catalogQueryUseCase.categoryTree());
    }
}
