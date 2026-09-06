package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.PaymentIntentView;
import com.flashsale.application.port.in.dto.PaymentView;

import java.util.Map;

/** 付款入站埠。 */
public interface PaymentUseCase {

    /** 為訂單發起付款。 */
    PaymentIntentView initiate(String orderNo, Long userId);

    /** 處理金流閘道的回調。 */
    void handleGatewayCallback(Map<String, String> parameters);

    PaymentView findByOrderNo(String orderNo, Long userId);
}
