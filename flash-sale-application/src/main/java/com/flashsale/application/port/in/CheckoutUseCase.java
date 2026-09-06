package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.CheckoutPreview;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.domain.shipping.ShippingMethod;

/** 從購物車結帳。 */
public interface CheckoutUseCase {

    /**
     * @param requestId 端到端冪等鍵。重送同一個值會拿回同一張訂單，
     * 而不是下第二單
     * @param addressId 收貨地址；訂單存的是它的快照而非這個 ID
     */
    /** 不用券的結帳。 */
    default OrderView checkout(Long userId, String requestId, Long addressId) {
        return checkout(userId, requestId, addressId, null, ShippingMethod.HOME_DELIVERY);
    }

    /**
     * @param couponId 要使用的優惠券；不用券時為 {@code null}。
     * 券的核銷與訂單建立在同一個交易裡——分開做的話，
     * 建單失敗時券會白白消失（ADR-0013 決策 7）
     */
    default OrderView checkout(Long userId, String requestId, Long addressId, Long couponId,
                               ShippingMethod shippingMethod) {
        return checkout(userId, requestId, addressId, couponId, shippingMethod, null);
    }

    OrderView checkout(Long userId, String requestId, Long addressId, Long couponId,
                       ShippingMethod shippingMethod, String buyerNote);

    /**
     * 購物車結帳試算：不建訂單、不扣庫存、不核銷券。
     * @param addressId 用來算運費的收貨地址；{@code null} 代表使用者還沒選，
     * 此時運費算不出來（回 0 且 {@code shippingKnown} 為 false）
     */
    CheckoutPreview preview(Long userId, Long couponId, Long addressId);
}
