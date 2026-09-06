package com.flashsale.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 後台銷售報表。 */
public interface SalesReportUseCase {

    /**
     * 期間內的銷售概況。
     *
     * <p><b>只算已付款的訂單</b>：待付款的還不是營收，而把它們算進去
     * 會讓報表在逾時關單之後自己往下掉——那種數字沒有人敢用。
     */
    Summary summary(LocalDate from, LocalDate to);

    /** 逐日走勢，供畫圖。 */
    List<DailyPoint> daily(LocalDate from, LocalDate to);

    /** 期間內的熱銷商品。 */
    List<TopProduct> topProducts(LocalDate from, LocalDate to, int limit);

    /**
     * @param revenue    已付款訂單的商品實付總額（不含運費）
     * @param refunded   期間內退掉的金額，正數
     * @param netRevenue {@code revenue - refunded}
     */
    record Summary(long paidOrders, BigDecimal revenue, BigDecimal refunded,
                   BigDecimal netRevenue, BigDecimal averageOrderValue, long itemsSold) {
    }

    record DailyPoint(LocalDate date, long paidOrders, BigDecimal revenue) {
    }

    record TopProduct(Long productId, String productName, long quantity, BigDecimal revenue) {
    }
}
