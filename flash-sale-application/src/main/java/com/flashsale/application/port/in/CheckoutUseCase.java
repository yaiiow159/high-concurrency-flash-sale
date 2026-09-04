package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.CheckoutPreview;
import com.flashsale.application.port.in.dto.OrderView;

/**
 * 從購物車結帳。
 *
 * <p>與 {@link PlaceOrderUseCase} 的差別只在「品項從哪裡來」：
 * 那邊由呼叫端指定，這邊從購物車讀。<b>下單邏輯完全共用</b>——
 * 價格重新取、庫存扣減、地址快照、冪等全部沿用同一條路徑。
 *
 * <p>另外多做兩件購物車特有的事：結帳前擋下已下架的品項，
 * 以及成功後清空購物車。兩者都必須與下單在<b>同一個交易</b>裡——
 * 訂單建立了但購物車沒清，使用者會重複下單。
 */
public interface CheckoutUseCase {

    /**
     * @param requestId 端到端冪等鍵。重送同一個值會拿回同一張訂單，
     *                  而不是下第二單
     * @param addressId 收貨地址；訂單存的是它的快照而非這個 ID
     */
    /** 不用券的結帳。 */
    default OrderView checkout(Long userId, String requestId, Long addressId) {
        return checkout(userId, requestId, addressId, null);
    }

    /**
     * @param couponId 要使用的優惠券；不用券時為 {@code null}。
     *                 券的核銷與訂單建立在同一個交易裡——分開做的話，
     *                 建單失敗時券會白白消失（ADR-0013 決策 7）
     */
    OrderView checkout(Long userId, String requestId, Long addressId, Long couponId);

    /**
     * 購物車結帳試算：不建訂單、不扣庫存、不核銷券。
     *
     * <p>品項與 {@link #checkout} 取自同一個來源（伺服器端購物車），
     * 這樣「試算看到的」與「真正下單的」才不會是兩件事。
     */
    CheckoutPreview preview(Long userId, Long couponId);
}
