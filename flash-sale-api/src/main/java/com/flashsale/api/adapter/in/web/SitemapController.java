package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.CatalogQueryUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** sitemap 的資料來源。前端據此產生 XML——組 URL 是前端的事，它才知道自己的網域。 */
@RestController
@Tag(name = "SEO", description = "sitemap 資料來源")
public class SitemapController {

    private final CatalogQueryUseCase catalogQuery;

    public SitemapController(CatalogQueryUseCase catalogQuery) {
        this.catalogQuery = catalogQuery;
    }

    @GetMapping("/api/v1/catalog/sitemap")
    @Operation(summary = "上架商品 id", description = "依 id 遞增分頁，附總數供索引算分片數")
    public ApiResponse<CatalogQueryUseCase.SitemapPage> productIds(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10000") int size) {
        return ApiResponse.ok(catalogQuery.sitemapProductIds(page, size));
    }
}
