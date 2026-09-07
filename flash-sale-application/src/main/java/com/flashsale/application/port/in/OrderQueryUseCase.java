package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.OrderView;

import java.util.List;
import java.util.Map;

/** 訂單查詢入站埠，供前端在拿到受理憑證後輪詢最終結果。 */
public interface OrderQueryUseCase {

    /** 依訂單編號查詢。 */
    OrderView findByOrderNo(String orderNo, Long userId);

    /**
     * 某使用者的訂單列表，新到舊。
     * 我的訂單。
     *
     * @param status 只看某個狀態；{@code null} 或空字串代表全部。
     * 訂單一多就只能一直往下捲，而使用者要找的通常是
     * 「待付款」或「待收貨」那幾筆
     */
    List<OrderView> listForUser(Long userId, String status, int page, int size);

    /** 各狀態的筆數，供帳戶總覽顯示「待付款 2、運送中 1」。 */
    Map<String, Long> summaryForUser(Long userId);
}
