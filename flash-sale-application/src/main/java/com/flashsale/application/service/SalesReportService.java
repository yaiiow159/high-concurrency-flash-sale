package com.flashsale.application.service;

import com.flashsale.application.port.in.SalesReportUseCase;
import com.flashsale.application.port.out.SalesReportRepository;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 後台銷售報表。 */
@Service
public class SalesReportService implements SalesReportUseCase {

    /**
     * 查詢區間上限。
     *
     * <p>沒有上限的話，一次「查全部」會在訂單表上掃全表——
     * 而後台通常跟前台共用同一個資料庫。
     */
    private static final int MAX_RANGE_DAYS = 366;

    private static final int MAX_TOP_PRODUCTS = 50;

    private final SalesReportRepository reportRepository;
    private final Clock clock;

    public SalesReportService(SalesReportRepository reportRepository, Clock clock) {
        this.reportRepository = reportRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to) {
        Range range = Range.of(from, to, clock);
        return reportRepository.summarize(range.from(), range.to());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyPoint> daily(LocalDate from, LocalDate to) {
        Range range = Range.of(from, to, clock);
        return reportRepository.daily(range.from(), range.to());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopProduct> topProducts(LocalDate from, LocalDate to, int limit) {
        Range range = Range.of(from, to, clock);
        return reportRepository.topProducts(range.from(), range.to(),
                Math.clamp(limit, 1, MAX_TOP_PRODUCTS));
    }

    /**
     * 查詢區間。
     *
     * <p>結束日<b>含當天</b>：使用者選「9/1 到 9/6」時期待看到 9/6 的資料，
     * 而不是到 9/6 零點為止。少了這一天是報表最常見的錯，
     * 而且它會安靜地一直少。
     */
    private record Range(Instant from, Instant to) {

        static Range of(LocalDate from, LocalDate to, Clock clock) {
            LocalDate end = to == null ? LocalDate.now(clock) : to;
            LocalDate start = from == null ? end.minusDays(29) : from;
            if (start.isAfter(end)) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "開始日不可晚於結束日");
            }
            if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "查詢區間不可超過 " + MAX_RANGE_DAYS + " 天");
            }
            return new Range(
                    start.atStartOfDay(clock.getZone()).toInstant(),
                    end.plusDays(1).atStartOfDay(clock.getZone()).toInstant());
        }
    }
}
