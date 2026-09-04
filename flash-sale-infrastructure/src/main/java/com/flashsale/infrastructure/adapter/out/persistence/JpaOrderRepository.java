package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.infrastructure.adapter.out.persistence.entity.OrderEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.OrderJpaRepository;
import com.flashsale.infrastructure.adapter.out.persistence.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 訂單持久化埠的 JPA 實作。 */
@Repository
public class JpaOrderRepository implements OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaOrderRepository.class);

    private final OrderJpaRepository jpaRepository;

    public JpaOrderRepository(OrderJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>先查再寫，然後仍然接住唯一鍵衝突</b>——這不是多餘。
     * 「先查再寫」在並發下本來就有競態窗口，只能減少衝突發生的頻率，不能消除它。
     * 真正保證正確的是資料庫的唯一索引；這裡把它的例外翻譯成正常的業務結果，
     * 讓應用層不必認得 Spring 的 {@code DataIntegrityViolationException}。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Order> saveIfAbsent(Order order) {
        if (jpaRepository.existsByRequestId(order.requestId())) {
            return Optional.empty();
        }
        try {
            OrderEntity saved = jpaRepository.saveAndFlush(OrderMapper.toEntity(order));
            return Optional.of(OrderMapper.toDomain(saved));
        } catch (DataIntegrityViolationException e) {
            log.debug("requestId={} 觸發唯一鍵衝突，判定為重複請求", order.requestId());
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>只更新狀態相關欄位。訂單行與金額在建立後不可變——
     * 那是金額能安全反正規化的前提（見 ADR-0007）。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Order update(Order order) {
        OrderEntity entity = jpaRepository.findByOrderNo(order.orderNo().value())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                        "更新時找不到訂單 " + order.orderNo()));
        entity.applyStateChange(order.status().name(), order.paidAt(), order.closeReason());
        return OrderMapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findByOrderNo(OrderNo orderNo) {
        return jpaRepository.findByOrderNo(orderNo.value()).map(OrderMapper::toDomain);
    }

    @Override
    @Transactional
    public Optional<Order> findByOrderNoForUpdate(OrderNo orderNo) {
        return jpaRepository.findByOrderNoForUpdate(orderNo.value()).map(OrderMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findByRequestId(String requestId) {
        return jpaRepository.findByRequestId(requestId).map(OrderMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public long sumActiveQuantity(Long activityId) {
        return jpaRepository.sumActiveQuantityByActivity(activityId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findByUserId(Long userId, String status, int limit, int offset) {
        return jpaRepository
                .findByUserIdAndStatus(userId,
                        status == null || status.isBlank() ? null : status,
                        PageRequest.of(offset / Math.max(limit, 1), limit))
                .stream()
                .map(OrderMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> findExistingOrderNos(Collection<String> orderNos) {
        if (orderNos.isEmpty()) {
            // 空集合會產生 `in ()` 這種在部分資料庫上非法的 SQL，先擋掉。
            return Set.of();
        }
        return Set.copyOf(jpaRepository.findExistingOrderNos(orderNos));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findExpiredPendingOrders(Instant deadline, int limit) {
        return jpaRepository.findExpiredPending(deadline, Limit.of(limit)).stream()
                .map(OrderMapper::toDomain)
                .toList();
    }
}
