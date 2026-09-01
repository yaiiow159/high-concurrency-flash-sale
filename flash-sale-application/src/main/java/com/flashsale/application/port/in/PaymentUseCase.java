package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.PaymentIntentView;
import com.flashsale.application.port.in.dto.PaymentView;

import java.util.Map;

/** 付款入站埠。 */
public interface PaymentUseCase {

    /**
     * 為訂單發起付款。
     *
     * <p>同一訂單重複發起會沿用既有的付款單：已成功的直接回傳，
     * 進行中的回傳原本的付款網址——使用者連點兩次不該產生兩張付款單。
     */
    PaymentIntentView initiate(String orderNo, Long userId);

    /**
     * 處理金流閘道的回調。
     *
     * <p><b>實作必須先驗簽再看內容。</b> 這個端點對外開放，
     * 少了簽章驗證，任何人送一個「付款成功」就能免費下單。
     *
     * <p>也必須冪等——真實閘道會重送回調，有些會送三、四次。
     */
    void handleGatewayCallback(Map<String, String> parameters);

    PaymentView findByOrderNo(String orderNo, Long userId);
}
