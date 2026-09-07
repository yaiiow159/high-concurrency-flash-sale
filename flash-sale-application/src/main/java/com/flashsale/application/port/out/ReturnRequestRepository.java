package com.flashsale.application.port.out;

import com.flashsale.domain.aftersales.ReturnNo;
import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.aftersales.ReturnStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 退貨單持久化埠（出站）。 */
public interface ReturnRequestRepository {

    ReturnRequest save(ReturnRequest request);

    ReturnRequest update(ReturnRequest request);

    Optional<ReturnRequest> findByReturnNo(ReturnNo returnNo);

    /** 冪等查詢：同一個 requestId 只該有一張退貨單。 */
    Optional<ReturnRequest> findByRequestId(String requestId);

    /** 某張訂單的所有退貨單。 */
    List<ReturnRequest> findByOrderNo(String orderNo);

    List<ReturnRequest> findByUserId(Long userId, int limit, int offset);

    /** 客服後台的待審清單。 */
    List<ReturnRequest> findByStatus(ReturnStatus status, int limit);

    /** 已核可退款、錢卻還沒出去且超過寬限期的退貨單。補送排程的工作集。 */
    List<ReturnRequest> findStuckRefunds(Instant startedBefore, int limit);

    /** 待到帳的退款筆數。正常應為 0，持續大於 0 代表閘道那一步卡住了。 */
    long countAwaitingSettlement();
}
