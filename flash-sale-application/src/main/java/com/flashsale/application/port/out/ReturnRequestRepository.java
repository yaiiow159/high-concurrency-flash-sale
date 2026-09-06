package com.flashsale.application.port.out;

import com.flashsale.domain.aftersales.ReturnNo;
import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.aftersales.ReturnStatus;

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
}
