package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.CartUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 登入後把本地購物車併入伺服器端。 */
public record CartMergeRequest(

        @NotNull(message = "items 不可為空")
        @Size(max = 50, message = "本地購物車最多 50 種商品")
        @Valid
        List<Item> items
) {

    public List<CartUseCase.LocalItem> toLocalItems() {
        return items.stream()
                .map(item -> new CartUseCase.LocalItem(item.skuId(), item.quantity()))
                .toList();
    }

    public record Item(
            @NotNull Long skuId,
            @Min(1) @Max(999) int quantity
    ) {
    }
}
