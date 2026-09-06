package com.flashsale.application.port.out;

import com.flashsale.domain.shipping.ShippingRate;

import java.util.List;

/** 運費費率的持久化埠（出站）。 */
public interface ShippingRateRepository {

    /** 全部費率。 */
    List<ShippingRate> findAll();
}
