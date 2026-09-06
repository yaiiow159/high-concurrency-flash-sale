package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.in.SalesReportUseCase;
import com.flashsale.application.port.out.SalesReportRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 銷售報表查詢。
 *
 * <p><b>只算已付款以後的訂單</b>（PAID / SHIPPED / COMPLETED / REFUNDED）：
 * 待付款的還不是營收，算進去會讓報表在逾時關單之後自己往下掉。
 *
 * <p>已退款的訂單仍然計入 revenue，退掉的金額另外用 refunded 表示——
 * 直接從 revenue 扣掉的話就看不出「賣了多少」與「退了多少」，
 * 而那兩個數字要分開看才有意義。
 */
@Repository
public class JpaSalesReportRepository implements SalesReportRepository {

    /** 已收到錢的訂單狀態。REFUNDED 也算——錢確實收過。 */
    private static final String PAID_STATUSES = "('PAID','SHIPPED','COMPLETED','REFUNDED')";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 概況。
     *
     * <p><b>件數的衍生表裡也要帶上同一組 WHERE。</b> 只寫在外層的話，
     * 衍生表會先把整張 {@code order_line} 聚合成暫存表再 join——
     * 查一天和查一年做同樣的工。實測 1.07 秒 vs 0.0018 秒，結果逐欄相同。
     *
     * <p>不可以偷懶改成平鋪的 {@code left join order_line}：訂單會被行數放大，
     * 實測 35 筆變 51 筆、營收多算 74%。那種錯不會有任何東西發現。
     */
    @Override
    @Transactional(readOnly = true)
    public SalesReportUseCase.Summary summarize(Instant from, Instant to) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        select count(*), coalesce(sum(o.total_amount), 0),
                               coalesce(sum(l.qty), 0)
                        from orders o
                        left join (select l.order_id, sum(l.quantity) qty
                                   from order_line l
                                   join orders o2 on o2.id = l.order_id
                                   where o2.status in %s
                                     and o2.paid_at >= :from and o2.paid_at < :to
                                   group by l.order_id) l on l.order_id = o.id
                        where o.status in %s and o.paid_at >= :from and o.paid_at < :to
                        """.formatted(PAID_STATUSES, PAID_STATUSES))
                .setParameter("from", Timestamp.from(from))
                .setParameter("to", Timestamp.from(to))
                .getSingleResult();

        long paidOrders = ((Number) row[0]).longValue();
        BigDecimal revenue = (BigDecimal) row[1];
        long itemsSold = ((Number) row[2]).longValue();

        BigDecimal refunded = (BigDecimal) entityManager.createNativeQuery("""
                        select coalesce(sum(rl.refund_amount), 0)
                        from return_line rl
                        join return_request r on r.id = rl.return_id
                        where r.status = 'REFUNDED'
                          and r.refunded_at >= :from and r.refunded_at < :to
                        """)
                .setParameter("from", Timestamp.from(from))
                .setParameter("to", Timestamp.from(to))
                .getSingleResult();

        // 平均客單價用已付款訂單數當分母，不是全部訂單——
        // 把沒付錢的算進去會讓這個數字永遠偏低而且沒有意義
        BigDecimal average = paidOrders == 0
                ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(paidOrders), 2, RoundingMode.HALF_UP);

        return new SalesReportUseCase.Summary(paidOrders, revenue, refunded,
                revenue.subtract(refunded), average, itemsSold);
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<SalesReportUseCase.DailyPoint> daily(Instant from, Instant to) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select date(paid_at), count(*), coalesce(sum(total_amount), 0)
                        from orders
                        where status in %s and paid_at >= :from and paid_at < :to
                        group by date(paid_at) order by date(paid_at)
                        """.formatted(PAID_STATUSES))
                .setParameter("from", Timestamp.from(from))
                .setParameter("to", Timestamp.from(to))
                .getResultList();
        return rows.stream()
                .map(row -> new SalesReportUseCase.DailyPoint(
                        ((Date) row[0]).toLocalDate(),
                        ((Number) row[1]).longValue(),
                        (BigDecimal) row[2]))
                .toList();
    }

    /**
     * 熱銷商品。
     *
     * <p>金額用 {@code allocated_amount} 而非 {@code unit_price × quantity}：
     * 有折扣時後者是使用者沒付過的錢，那會讓報表比實際收入高。
     */
    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<SalesReportUseCase.TopProduct> topProducts(Instant from, Instant to, int limit) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select s.product_id, max(p.name),
                               sum(l.quantity), sum(l.allocated_amount)
                        from order_line l
                        join orders o on o.id = l.order_id
                        join sku s on s.id = l.sku_id
                        join product p on p.id = s.product_id
                        where o.status in %s and o.paid_at >= :from and o.paid_at < :to
                        group by s.product_id
                        order by sum(l.quantity) desc, s.product_id asc
                        limit :limit
                        """.formatted(PAID_STATUSES))
                .setParameter("from", Timestamp.from(from))
                .setParameter("to", Timestamp.from(to))
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream()
                .map(row -> new SalesReportUseCase.TopProduct(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        ((Number) row[2]).longValue(),
                        (BigDecimal) row[3]))
                .toList();
    }
}
