package com.flashsale.application.port.in;

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
    OrderView checkout(Long userId, String requestId, Long addressId);
}
