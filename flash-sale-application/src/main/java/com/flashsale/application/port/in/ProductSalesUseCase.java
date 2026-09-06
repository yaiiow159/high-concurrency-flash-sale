package com.flashsale.application.port.in;

import java.util.Map;

/** 商品銷量的計入與扣回。 */
public interface ProductSalesUseCase {

    /** 把一張已付款訂單計入銷量。 */
    boolean recordSale(String orderNo, Long userId);

    /** 退貨扣回。 */
    boolean recordReturn(String returnNo, Map<Long, Integer> quantityBySku);
}
