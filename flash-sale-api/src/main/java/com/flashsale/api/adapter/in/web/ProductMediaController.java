package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.AttachImageRequest;
import com.flashsale.api.adapter.in.web.dto.UploadAuthorizationRequest;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.ProductMediaUseCase;
import com.flashsale.application.port.in.dto.ProductImageView;
import com.flashsale.application.port.in.dto.UploadAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 商品圖片（ADR-0027）。
 *
 * <p>管理端點的授權由 {@code SecurityConfig} 統一在 {@code /api/v1/admin/**}
 * 上要求 admin scope——不在這裡各自標註 {@code @PreAuthorize}：
 * 兩個地方都能決定授權的話，遲早會有一支端點只改了其中一邊。
 *
 * <p>上傳是<b>兩步</b>：先要授權、瀏覽器直傳物件儲存、再回報掛載。
 * 這裡<b>沒有任何一支端點會收到檔案位元組</b>——那是刻意的，
 * 一張 5 MB 的圖會把請求執行緒佔住數秒，而那個執行緒池
 * 是秒殺熱路徑要用的。
 */
@Tag(name = "商品圖片")
@RestController
public class ProductMediaController {

    private final ProductMediaUseCase productMediaUseCase;

    public ProductMediaController(ProductMediaUseCase productMediaUseCase) {
        this.productMediaUseCase = productMediaUseCase;
    }

    /**
     * 商品的圖片。<b>公開</b>——商品頁不帶身分也要看得到圖。
     */
    @GetMapping("/api/v1/catalog/products/{productId}/images")
    @Operation(summary = "商品圖片", description = "依排序，第一張是主圖")
    public ApiResponse<List<ProductImageView>> images(@PathVariable Long productId) {
        return ApiResponse.ok(productMediaUseCase.imagesOf(productId));
    }

    /**
     * 批次取主圖，供列表一次帶整頁。
     *
     * <p>不是每張卡各打一次——那是 N+1 在前端的樣子，
     * 而這個專案已經在商品列表上踩過一次了。
     */
    @GetMapping("/api/v1/catalog/products/images")
    @Operation(summary = "批次取主圖", description = "一次帶整頁的商品，不是每張卡各打一次")
    public ApiResponse<Map<Long, ProductImageView>> primaryImages(
            @RequestParam List<Long> productIds) {
        return ApiResponse.ok(productMediaUseCase.primaryImagesOf(productIds));
    }

    /**
     * 要一張上傳授權。
     *
     * <p>回 {@code alreadyUploaded=true} 時前端可以<b>跳過上傳</b>直接掛載——
     * 內容雜湊命名讓重複上傳變成零成本。
     */
    @PostMapping("/api/v1/admin/products/images/authorize")
    @Operation(summary = "取得上傳授權",
            description = "回預簽名 PUT URL；位元組由瀏覽器直傳，不經過應用伺服器")
    public ApiResponse<UploadAuthorization> authorize(
            @Valid @RequestBody UploadAuthorizationRequest request,
            @CurrentUser Long userId) {

        return ApiResponse.ok(productMediaUseCase.authorizeUpload(
                userId, request.sha256(), request.contentType(), request.byteSize()));
    }

    /**
     * 上傳完成，掛到商品上。
     *
     * <p>回 {@code 201}：一個關聯真的被建立了。
     */
    @PostMapping("/api/v1/admin/products/{productId}/images")
    @Operation(summary = "掛載圖片", description = "會先確認物件真的在儲存裡，不相信前端說的")
    public ResponseEntity<ApiResponse<ProductImageView>> attach(
            @PathVariable Long productId,
            @Valid @RequestBody AttachImageRequest request) {

        ProductImageView image = productMediaUseCase.attach(
                productId, request.objectKey(), request.contentType(), request.byteSize());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(image));
    }

    /**
     * 取消掛載。
     *
     * <p><b>不刪物件</b>（ADR-0027 決策 5）：物件儲存不能參與資料庫交易，
     * 而先刪物件的失敗模式是破圖。留下孤兒交給對帳。
     */
    @DeleteMapping("/api/v1/admin/products/{productId}/images/{imageId}")
    @Operation(summary = "取消掛載", description = "只刪關聯，物件保留交由對帳處理")
    public ApiResponse<Void> detach(@PathVariable Long productId, @PathVariable Long imageId) {
        productMediaUseCase.detach(productId, imageId);
        return ApiResponse.ok(null);
    }
}
