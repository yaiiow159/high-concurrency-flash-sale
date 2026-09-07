package com.flashsale.infrastructure.adapter.out.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
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
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import com.flashsale.infrastructure.tracing.TraceContexts;

/** 訂單持久化埠的 JPA 實作。 */
@Repository
public class JpaOrderRepository implements OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaOrderRepository.class);

    private final OrderJpaRepository jpaRepository;
    private final TraceContexts traceContexts;

    @PersistenceContext
    private EntityManager entityManager;

    public JpaOrderRepository(OrderJpaRepository jpaRepository,
                              TraceContexts traceContexts) {
        this.jpaRepository = jpaRepository;
        this.traceContexts = traceContexts;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Order> saveIfAbsent(Order order) {
        if (jpaRepository.existsByRequestId(order.requestId())) {
            return Optional.empty();
        }
        try {
            OrderEntity entity = OrderMapper.toEntity(order);
            // 消費端在還原出來的 span 底下建單，這裡拿到的就是整條鏈的 trace id
            traceContexts.currentTraceId().ifPresent(entity::attachTrace);
            OrderEntity saved = jpaRepository.saveAndFlush(entity);
            return Optional.of(OrderMapper.toDomain(saved));
        } catch (DataIntegrityViolationException e) {
            log.debug("requestId={} 觸發唯一鍵衝突，判定為重複請求", order.requestId());
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
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
    @Transactional(readOnly = true)
    public Optional<String> findStaffNote(OrderNo orderNo) {
        return jpaRepository.findByOrderNo(orderNo.value())
                .map(com.flashsale.infrastructure.adapter.out.persistence.entity
                        .OrderEntity::staffNote);
    }

    @Override
    @Transactional
    public void updateStaffNote(OrderNo orderNo, String note) {
        jpaRepository.updateStaffNote(orderNo.value(), note);
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
                        Pageables.of(limit, offset))
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
    public Optional<String> findTraceId(OrderNo orderNo) {
        return jpaRepository.findTraceId(orderNo.value());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> countByUserIdGroupedByStatus(Long userId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : jpaRepository.countByUserIdGroupedByStatus(userId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findExpiredPendingOrders(Instant deadline, int limit) {
        // 兩段式：先用覆蓋索引取 ID（帶 limit），再對那幾筆 join fetch 訂單行。
        // 併成一句會踩上 HHH90003004——Hibernate 會把符合條件的訂單全部載入
        // 再切出 limit 筆，而這條路徑正是逾時關單的止血動作
        List<Long> ids = jpaRepository.findExpiredPendingIds(deadline, Limit.of(limit));
        if (ids.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByIdInOrderByCreatedAtAsc(ids).stream()
                .map(OrderMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> search(SearchCriteria criteria, int limit, int offset) {
        TypedQuery<OrderEntity> query = entityManager.createQuery(
                "select o from OrderEntity o" + searchWhere(criteria) + " order by o.createdAt desc",
                OrderEntity.class);
        bindSearch(query, criteria);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList().stream()
                .map(OrderMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countSearch(SearchCriteria criteria) {
        TypedQuery<Long> query = entityManager.createQuery(
                "select count(o) from OrderEntity o" + searchWhere(criteria), Long.class);
        bindSearch(query, criteria);
        return query.getSingleResult();
    }

    /** 條件動態拼接而不是 `(:x is null or ...)`：後者讓 MySQL 每個條件都走不了索引。 */
    private static String searchWhere(SearchCriteria criteria) {
        List<String> clauses = new ArrayList<>();
        if (criteria.orderNo() != null) {
            clauses.add("o.orderNo = :orderNo");
        }
        if (criteria.userId() != null) {
            clauses.add("o.userId = :userId");
        }
        if (criteria.status() != null) {
            clauses.add("o.status = :status");
        }
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    private static void bindSearch(TypedQuery<?> query, SearchCriteria criteria) {
        if (criteria.orderNo() != null) {
            query.setParameter("orderNo", criteria.orderNo());
        }
        if (criteria.userId() != null) {
            query.setParameter("userId", criteria.userId());
        }
        if (criteria.status() != null) {
            query.setParameter("status", criteria.status());
        }
    }
}
