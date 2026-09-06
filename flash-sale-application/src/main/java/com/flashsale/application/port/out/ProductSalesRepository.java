package com.flashsale.application.port.out;

import com.flashsale.domain.catalog.ProductSales;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 商品銷量聚合的持久化埠（出站）。 */
public interface ProductSalesRepository {

    /** 計入一張訂單的銷量。 */
    boolean applySale(String orderNo, Map<Long, Integer> quantityByProduct);

    /** 退貨扣回。 */
    boolean applyReturn(String returnNo, Map<Long, Integer> quantityByProduct);

    /** 批次取回，供列表頁一次帶出整頁的銷量。 */
    List<ProductSales> findByProductIds(Collection<Long> productIds);
}
