package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.CartUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登入後把本地購物車併入伺服器端。
 *
 * <p>本地購物車的內容<b>完全由前端提供，一律不可信</b>：
 * 它可能放了好幾天、可能含已下架的商品、也可能被人手動改過 localStorage。
 * Use Case 會逐筆驗證並略過不合法的，而不是整批拒絕——
 * 讓登入因為購物車裡有一件下架商品而失敗，是把系統的內部狀態變成使用者的問題。
 */
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
