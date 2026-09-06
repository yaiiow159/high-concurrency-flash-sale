package com.flashsale.application.port.in;

import com.flashsale.application.port.in.command.OpenReturnCommand;
import com.flashsale.application.port.in.dto.ReturnRequestView;
import com.flashsale.application.port.in.dto.ReturnableView;
import com.flashsale.domain.aftersales.ReturnStatus;

import java.util.List;
import java.util.Map;

/** 退貨退款（ADR-0011）——系統的第二個 Saga。 */
public interface ReturnUseCase {

    /** 這張訂單現在能退什麼。 */
    ReturnableView inspectReturnable(String orderNo, Long userId);

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
     * 漏掉時拋例外而非預設為可再售——
     * 那個預設值會把毀損品的成本靜靜地算成庫存
     */
    ReturnRequestView receive(String returnNo, Map<Long, Boolean> restockDecisions);

    /** 送出退款。 */
    ReturnRequestView refund(String returnNo);

    ReturnRequestView findForUser(String returnNo, Long userId);

    List<ReturnRequestView> listForUser(Long userId, int page, int size);

    /** 客服後台的待審清單。 */
    List<ReturnRequestView> listByStatus(ReturnStatus status, int limit);
}
