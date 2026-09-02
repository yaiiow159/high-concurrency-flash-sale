package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.CartView;

import java.util.List;

/**
 * 購物車。
 *
 * <p>每個方法都以 {@code userId} 為第一參數，與地址簿同理：
 * 讓「忘了限定擁有者」變成編譯期看得出來的疏忽。
 */
public interface CartUseCase {

    /** 讀取購物車，價格與商品名即時取自 Catalog。 */
    CartView view(Long userId);

    CartView addItem(Long userId, Long skuId, int quantity);

    /** 數量設為 0 等同移除。 */
    CartView changeQuantity(Long userId, Long skuId, int quantity);

    CartView removeItem(Long userId, Long skuId);

    void clear(Long userId);

    /**
     * 把未登入期間的本地購物車併入伺服器端。
     *
     * <p>登入後由前端呼叫一次。同一個 SKU 取兩邊較大值——理由見 {@code Cart.mergeFrom}。
     */
    CartView merge(Long userId, List<LocalItem> localItems);

    /** 前端本地購物車的品項。只有 SKU 與數量——價格不由前端決定。 */
    record LocalItem(Long skuId, int quantity) {
    }
}
