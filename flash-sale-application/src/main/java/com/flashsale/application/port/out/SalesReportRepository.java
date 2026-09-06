package com.flashsale.application.port.out;

import com.flashsale.application.port.in.SalesReportUseCase;

import java.time.Instant;
import java.util.List;

/** 銷售報表的查詢埠（出站）。 */
public interface SalesReportRepository {

    SalesReportUseCase.Summary summarize(Instant from, Instant to);

    List<SalesReportUseCase.DailyPoint> daily(Instant from, Instant to);

    List<SalesReportUseCase.TopProduct> topProducts(Instant from, Instant to, int limit);
}
