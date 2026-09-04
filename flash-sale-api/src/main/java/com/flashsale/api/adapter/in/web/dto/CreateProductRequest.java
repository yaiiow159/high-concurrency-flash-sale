package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.CatalogAdminUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 建立商品。
 *
 * <p><b>沒有 status 欄位。</b> 新建的商品一律是 {@code DRAFT}——
 * 讓呼叫端指定狀態等於讓它跳過「上架」這個獨立動作，
 * 而上架會觸發搜尋索引寫入。建立與曝光是兩件事（ADR-0015 決策 5）。
 */
public record CreateProductRequest(

        @NotNull(message = "請選擇類目")
        Long categoryId,

        @NotBlank(message = "商品名稱不可為空")
        @Size(max = 128, message = "商品名稱不可超過 128 字")
        String name,

        @Size(max = 64, message = "品牌不可超過 64 字")
        String brand,

        @Size(max = 1000, message = "商品描述不可超過 1000 字")
        String description,

        @NotEmpty(message = "商品至少要有一個規格")
        @Size(max = 50, message = "單一商品最多 50 個規格")
        @Valid
        List<SkuRequest> skus
) {

    public CatalogAdminUseCase.CreateProductCommand toCommand() {
        return new CatalogAdminUseCase.CreateProductCommand(categoryId, name, brand, description,
                skus.stream()
                        .map(sku -> new CatalogAdminUseCase.SkuSpecCommand(
                                sku.attributes(), sku.price(), sku.barcode()))
                        .toList());
    }

    /**
     * @param attributes 規格屬性，例如 {@code {"容量": "256G"}}。
     *                   用 Map 而不是固定欄位——不同品類的規格維度本來就不同
     */
    public record SkuRequest(

            @NotEmpty(message = "規格屬性不可為空")
            Map<String, String> attributes,

            @NotNull(message = "請填寫價格")
            @Positive(message = "價格必須大於 0")
            BigDecimal price,

            @Size(max = 64, message = "條碼不可超過 64 字")
            String barcode
    ) {
    }
}
