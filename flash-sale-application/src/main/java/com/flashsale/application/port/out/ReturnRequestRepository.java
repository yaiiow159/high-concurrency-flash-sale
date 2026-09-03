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

    /**
     * 某張訂單的所有退貨單。
     *
     * <p>計算「可退數量」的必經步驟——防重複退款的第二層要靠它。
     * 包含已駁回與已撤回的單，由呼叫端依 {@code holdsReturnQuota()} 過濾；
     * 在儲存庫這一層就篩掉，等於把領域規則埋進 SQL。
     */
    List<ReturnRequest> findByOrderNo(String orderNo);

    List<ReturnRequest> findByUserId(Long userId, int limit, int offset);

    /** 客服後台的待審清單。 */
    List<ReturnRequest> findByStatus(ReturnStatus status, int limit);
}
