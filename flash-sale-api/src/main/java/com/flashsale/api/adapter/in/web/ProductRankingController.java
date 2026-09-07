package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.ProductRankingUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 前台排行榜。匿名可讀：它不含身分，而它出現在可被 ISR 快取的首頁上。 */
@RestController
@Tag(name = "排行榜")
public class ProductRankingController {

    private final ProductRankingUseCase ranking;

    public ProductRankingController(ProductRankingUseCase ranking) {
        this.ranking = ranking;
    }

    @GetMapping("/api/v1/catalog/rankings")
    @Operation(summary = "熱銷排行", description = "最近 days 天內已付款訂單的銷量排序；已下架的商品不列")
    public ApiResponse<List<ProductRankingUseCase.RankedProduct>> bestSellers(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(ranking.bestSellers(days, limit));
    }
}
