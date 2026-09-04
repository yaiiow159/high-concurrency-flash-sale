package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.ProductSalesRepository;
import com.flashsale.domain.catalog.ProductSales;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 銷量聚合。
 *
 * <h2>增量 UPDATE，不是讀出來加完再寫回</h2>
 *
 * <p>CLAUDE.md 7-3 那條規則：{@code SET x = x + ?}。
 * 寫成 read-modify-write 的話，兩個人同時買同一件商品，
 * 兩邊都讀到 10、各自寫回 11，於是有一筆銷售從聚合上消失——
 * 而訂單表裡還在。
 *
 * <h2>冪等靠唯一索引，不靠先查再寫</h2>
 *
 * <p>「先查有沒有計入過、再決定要不要加」同樣是 read-modify-write，
 * 兩個並行的消費者都會通過檢查。真正的防線是
 * {@code uk_product_sales_applied} 這個唯一索引：
 * 先插入流水，插得進去才動聚合。
 */
@Repository
public class JpaProductSalesRepository implements ProductSalesRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaProductSalesRepository.class);

    private static final String SALE = "SALE";
    private static final String RETURN = "RETURN";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean applySale(String orderNo, Map<Long, Integer> quantityByProduct) {
        return apply(orderNo, quantityByProduct, SALE, 1);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean applyReturn(String returnNo, Map<Long, Integer> quantityByProduct) {
        return apply(returnNo, quantityByProduct, RETURN, -1);
    }

    private boolean apply(String refNo, Map<Long, Integer> quantityByProduct,
                          String direction, int sign) {
        if (quantityByProduct.isEmpty()) {
            return false;
        }
        if (!claim(refNo, direction)) {
            log.debug("{} 的 {} 已經計入過銷量，略過", refNo, direction);
            return false;
        }

        for (Map.Entry<Long, Integer> entry : quantityByProduct.entrySet()) {
            long quantity = (long) sign * entry.getValue();
            // upsert：第一次賣出的商品還沒有列。用 ON DUPLICATE KEY 而不是
            // 「先查再決定 insert 或 update」——後者又是一次 read-modify-write
            entityManager.createNativeQuery("""
                            insert into product_sales (product_id, sold_quantity, order_count)
                            values (:productId, :quantity, :orderDelta)
                            on duplicate key update
                                sold_quantity = sold_quantity + :quantity,
                                order_count = order_count + :orderDelta
                            """)
                    .setParameter("productId", entry.getKey())
                    .setParameter("quantity", quantity)
                    .setParameter("orderDelta", sign)
                    .executeUpdate();
        }
        return true;
    }

    /**
     * 佔位。插得進去代表這一筆還沒被計入過。
     *
     * <p>靠唯一索引擋重複，而不是靠先查再寫——後者兩個並行的消費者
     * 都會通過檢查，然後各加一次。
     */
    private boolean claim(String refNo, String direction) {
        try {
            entityManager.createNativeQuery("""
                            insert into product_sales_applied (ref_no, direction)
                            values (:refNo, :direction)
                            """)
                    .setParameter("refNo", refNo)
                    .setParameter("direction", direction)
                    .executeUpdate();
            // flush 讓唯一索引衝突在這裡就爆出來，而不是等到交易提交時——
            // 那時已經來不及決定「要不要動聚合」了
            entityManager.flush();
            return true;
        } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductSales> findByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            // 空集合會產生 `in ()` 這種在部分資料庫上非法的 SQL
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select product_id, sold_quantity, order_count
                        from product_sales where product_id in (:productIds)
                        """)
                .setParameter("productIds", productIds)
                .getResultList();

        return rows.stream()
                .map(row -> new ProductSales(
                        toLong(row[0]), toLong(row[1]), toLong(row[2])))
                .toList();
    }

    private static long toLong(Object value) {
        return value instanceof BigInteger big ? big.longValue() : ((Number) value).longValue();
    }
}
