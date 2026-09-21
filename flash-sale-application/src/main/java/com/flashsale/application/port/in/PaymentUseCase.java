package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.PaymentIntentView;
import com.flashsale.application.port.in.dto.PaymentView;
import com.flashsale.domain.payment.PaymentMethod;

import java.util.Map;

/** 付款入站埠。 */
public interface PaymentUseCase {

    /**
     * 為訂單發起付款。
     *
     * @param method 付款方式；重複發起時以最後一次選的為準，但只在付款還沒有結果時才改得動
     */
    PaymentIntentView initiate(String orderNo, Long userId, PaymentMethod method);

    /** 處理金流閘道的回調。 */
    void handleGatewayCallback(Map<String, String> parameters);

    PaymentView findByOrderNo(String orderNo, Long userId);
}
