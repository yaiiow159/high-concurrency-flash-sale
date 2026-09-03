package com.flashsale.application.port.in.command;

import com.flashsale.domain.aftersales.ReturnReason;

import java.util.List;

/**
 * 開立退貨申請。
 *
 * <p><b>沒有金額欄位。</b>退款金額一律由訂單行的快照單價乘上退貨數量算出——
 * 容許呼叫端指定金額，「累計退款 ≤ 已付金額」以外就沒有任何可驗證的依據了
 * （ADR-0011）。
 *
 * <p>也沒有「是否需要寄回」：那由訂單狀態決定。讓呼叫端自己宣告，
 * 「已出貨卻宣稱免寄回」就是一個免費拿貨的漏洞。
 *
 * @param items     要退的品項與數量。可以只退訂單的一部分，這就是部分退款
 * @param requestId 冪等鍵。<b>必須在送出前產生並在重試之間保留</b>——
 *                  每次重試都換新值的話，使用者網路逾時後再按一次，
 *                  同一批商品就會被申請兩次退貨
 */
public record OpenReturnCommand(
        String orderNo,
        Long userId,
        String requestId,
        List<Item> items,
        ReturnReason reason,
        String reasonDetail
) {

    public record Item(Long skuId, int quantity) {
    }
}
