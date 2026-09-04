package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.ReturnRequestRepository;
import com.flashsale.domain.aftersales.ReturnLine;
import com.flashsale.domain.aftersales.ReturnNo;
import com.flashsale.domain.aftersales.ReturnReason;
import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.aftersales.ReturnStatus;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.infrastructure.adapter.out.persistence.entity.ReturnLineEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.ReturnRequestEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.ReturnRequestJpaRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 退貨單持久化轉接器。
 *
 * <p>領域物件與 Entity 分離的成本在這裡最明顯——多一層轉換。
 * 換來的是領域層完全不認得 JPA，因此
 * {@code ReturnRequest} 的狀態機可以用純單元測試驗證，不需要資料庫。
 */
@Repository
public class JpaReturnRequestRepository implements ReturnRequestRepository {

    private final ReturnRequestJpaRepository jpaRepository;

    public JpaReturnRequestRepository(ReturnRequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public ReturnRequest save(ReturnRequest request) {
        ReturnRequestEntity entity = new ReturnRequestEntity(
                request.returnNo().value(),
                request.orderNo().value(),
                request.userId(),
                request.requestId(),
                request.reason().name(),
                request.reasonDetail(),
                request.requiresGoodsReturn(),
                request.status().name(),
                request.createdAt());
        for (ReturnLine line : request.lines()) {
            entity.addLine(new ReturnLineEntity(line.skuId(), line.skuSnapshot(),
                    line.unitPrice(), line.quantity(), line.restockable(), line.refundAmount()));
        }
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional
    public ReturnRequest update(ReturnRequest request) {
        ReturnRequestEntity entity = jpaRepository.findByReturnNo(request.returnNo().value())
                .orElseThrow(() -> new IllegalStateException(
                        "更新退貨單時找不到紀錄 returnNo=" + request.returnNo()));

        Map<Long, Boolean> restockableBySku = new HashMap<>();
        for (ReturnLine line : request.lines()) {
            restockableBySku.put(line.skuId(), line.restockable());
        }
        entity.applyStateChange(request.status().name(), request.reviewNote(),
                request.reviewedAt(), request.receivedAt(), request.refundedAt(),
                restockableBySku);
        return toDomain(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReturnRequest> findByReturnNo(ReturnNo returnNo) {
        return jpaRepository.findByReturnNo(returnNo.value())
                .map(JpaReturnRequestRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReturnRequest> findByRequestId(String requestId) {
        return jpaRepository.findByRequestId(requestId)
                .map(JpaReturnRequestRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnRequest> findByOrderNo(String orderNo) {
        return jpaRepository.findByOrderNo(orderNo).stream()
                .map(JpaReturnRequestRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnRequest> findByUserId(Long userId, int limit, int offset) {
        return jpaRepository.findByUserIdOrderByCreatedAtDesc(
                        userId, PageRequest.of(offset / Math.max(limit, 1), limit)).stream()
                .map(JpaReturnRequestRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnRequest> findByStatus(ReturnStatus status, int limit) {
        return jpaRepository.findByStatusOrderByCreatedAtAsc(status.name(), Limit.of(limit)).stream()
                .map(JpaReturnRequestRepository::toDomain)
                .toList();
    }

    private static ReturnRequest toDomain(ReturnRequestEntity entity) {
        List<ReturnLine> lines = entity.getLines().stream()
                .map(line -> new ReturnLine(line.getSkuId(), line.getSkuSnapshot(),
                        line.getUnitPrice(), line.getQuantity(), line.getRestockable(),
                        line.getRefundAmount()))
                .toList();
        return ReturnRequest.restore(
                entity.getId(),
                ReturnNo.of(entity.getReturnNo()),
                OrderNo.of(entity.getOrderNo()),
                entity.getUserId(),
                entity.getRequestId(),
                ReturnReason.valueOf(entity.getReason()),
                entity.getReasonDetail(),
                entity.isRequiresGoodsReturn(),
                lines,
                ReturnStatus.valueOf(entity.getStatus()),
                entity.getReviewNote(),
                entity.getCreatedAt(),
                entity.getReviewedAt(),
                entity.getReceivedAt(),
                entity.getRefundedAt(),
                entity.getVersion());
    }
}
