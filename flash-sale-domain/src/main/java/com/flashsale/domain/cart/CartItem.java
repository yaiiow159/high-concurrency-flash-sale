package com.flashsale.domain.cart;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/** 購物車品項。 */
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
