package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.HomeLayoutUseCase;
import com.flashsale.application.port.in.dto.HomeLayoutView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首頁內容。
 *
 * <p>匿名可讀且回應不含身分相關資料——那是首頁能被 CDN 與 ISR 快取的前提。
 */
@RestController
@Tag(name = "首頁", description = "版位與輪播圖")
public class HomeController {

    private final HomeLayoutUseCase homeLayout;

    public HomeController(HomeLayoutUseCase homeLayout) {
        this.homeLayout = homeLayout;
    }

    @GetMapping("/api/v1/home")
    @Operation(summary = "首頁版型", description = "依設定的順序回傳版位與內容，已過濾上架期間")
    public ApiResponse<HomeLayoutView> layout() {
        return ApiResponse.ok(homeLayout.currentLayout());
    }
}
