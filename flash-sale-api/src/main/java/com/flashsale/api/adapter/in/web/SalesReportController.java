package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.SalesReportUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 銷售報表。需要 {@code seckill:admin} scope（由 SecurityConfig 統一設定）。 */
@RestController
@RequestMapping("/api/v1/admin/reports/sales")
@Tag(name = "銷售報表", description = "營收、走勢與熱銷排行")
public class SalesReportController {

    private final SalesReportUseCase salesReport;

    public SalesReportController(SalesReportUseCase salesReport) {
        this.salesReport = salesReport;
    }

    @GetMapping("/summary")
    @Operation(summary = "銷售概況", description = "不指定日期時看最近 30 天")
    public ApiResponse<SalesReportUseCase.Summary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ApiResponse.ok(salesReport.summary(from, to));
    }

    @GetMapping("/daily")
    @Operation(summary = "逐日走勢")
    public ApiResponse<List<SalesReportUseCase.DailyPoint>> daily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ApiResponse.ok(salesReport.daily(from, to));
    }

    @GetMapping("/top-products")
    @Operation(summary = "熱銷排行", description = "金額用分攤後的實付算，不是定價")
    public ApiResponse<List<SalesReportUseCase.TopProduct>> topProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(salesReport.topProducts(from, to, limit));
    }
}
