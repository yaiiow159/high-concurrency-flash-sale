package com.flashsale.application.port.in;

import com.flashsale.application.port.in.command.OpenReturnCommand;
import com.flashsale.application.port.in.dto.ReturnRequestView;
import com.flashsale.domain.aftersales.ReturnStatus;

import java.util.List;
import java.util.Map;

/**
 * 退貨退款（ADR-0011）——系統的第二個 Saga。
 *
 * <p>與下單補償的差別在於它要還原<b>兩樣東西</b>：錢和貨。
 * 而這兩樣在不同時間點移動——錢可以馬上退，貨要等買家寄回。
 *
 * <p><b>重複執行的代價不對稱</b>：下單補償多退一次庫存只是少賣，
 * 這裡多退一次錢就是直接虧損，沒有任何事後對帳能補救。
 * 因此防重複是三層（退貨單狀態機、訂單行累計數量、付款聚合根）。
 */
public interface ReturnUseCase {

    /** 買家開立退貨申請。可以只退訂單的一部分。 */
    ReturnRequestView open(OpenReturnCommand command);

    /** 買家撤回自己的申請。貨一旦收下就不能再撤。 */
    ReturnRequestView cancel(String returnNo, Long userId);

    /** 客服核准。 */
    ReturnRequestView approve(String returnNo, String note);

    /** 客服駁回。必須說明理由——駁回而不說原因會直接變成客訴。 */
    ReturnRequestView reject(String returnNo, String note);

    /**
     * 收到退回品並完成驗收。
     *
     * @param restockDecisions skuId → 是否可再售。必須涵蓋每一行，
     *                         漏掉時拋例外而非預設為可再售——
     *                         那個預設值會把毀損品的成本靜靜地算成庫存
     */
    ReturnRequestView receive(String returnNo, Map<Long, Boolean> restockDecisions);

    /**
     * 送出退款。
     *
     * <p>在同一個交易裡扣減付款聚合根的可退額度、寫入 outbox 事件，
     * 實際的金流呼叫與庫存回補交給消費端（ADR-0011 決策 8）。
     */
    ReturnRequestView refund(String returnNo);

    ReturnRequestView findForUser(String returnNo, Long userId);

    List<ReturnRequestView> listForUser(Long userId, int page, int size);

    /** 客服後台的待審清單。 */
    List<ReturnRequestView> listByStatus(ReturnStatus status, int limit);
}
