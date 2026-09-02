package com.flashsale.domain.cart;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 購物車品項。
 *
 * <p><b>只有 SKU 與數量，沒有價格也沒有商品名。</b>
 * 那些每次顯示時從 Catalog 取——購物車要回答的是「現在買要多少錢」，
 * 存下來的快照會在商家調價後變成謊言。
 *
 * <p>{@code updatedAt} 用來排序與清理長期未動的購物車，
 * 不參與任何業務判斷。
 */
public record CartItem(Long skuId, int quantity, Instant updatedAt) {

    public CartItem {
        Objects.requireNonNull(skuId, "skuId 不可為 null");
        Objects.requireNonNull(updatedAt, "updatedAt 不可為 null");
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "購物車品項數量必須大於 0");
        }
    }

    public CartItem withQuantity(int newQuantity, Instant now) {
        return new CartItem(skuId, newQuantity, now);
    }
}
