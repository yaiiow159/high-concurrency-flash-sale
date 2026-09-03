package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.OpenReturnRequest;
import com.flashsale.api.adapter.in.web.dto.ReturnReceiveRequest;
import com.flashsale.api.adapter.in.web.dto.ReturnReviewRequest;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.ReturnUseCase;
import com.flashsale.application.port.in.command.OpenReturnCommand;
import com.flashsale.application.port.in.dto.ReturnRequestView;
import com.flashsale.domain.aftersales.ReturnStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 退貨退款 API（ADR-0011）。
 *
 * <p>與履約 API 同樣<b>拆成兩段路徑</b>，因為讀者不同：
 *
 * <ul>
 *   <li>{@code /api/v1/orders/{orderNo}/returns} 與 {@code /api/v1/returns/**}
 *       —— 買家申請、查詢、撤回自己的退貨單，以令牌的身分為界</li>
 *   <li>{@code /api/v1/admin/returns/**} —— 客服審核、驗收、送出退款，
 *       需要 {@code seckill:admin} scope</li>
 * </ul>
 *
 * <p>把兩者混在同一段路徑靠參數區分權限，少一個判斷買家就能自己核准退款
 * 並把錢退給自己。這條路徑上那不是漏洞，是提款機。
 */
@RestController
@Tag(name = "退貨退款", description = "退貨申請、審核、驗收與退款")
public class ReturnController {

    private final ReturnUseCase returnUseCase;

    public ReturnController(ReturnUseCase returnUseCase) {
        this.returnUseCase = returnUseCase;
    }

    // ---- 買家 ----

    @PostMapping("/api/v1/orders/{orderNo}/returns")
    @Operation(summary = "申請退貨", description = "可以只退訂單的一部分；金額由訂單快照算出")
    public ApiResponse<ReturnRequestView> open(@PathVariable String orderNo,
                                               @Valid @RequestBody OpenReturnRequest request,
                                               @CurrentUser Long userId) {
        List<OpenReturnCommand.Item> items = request.items().stream()
                .map(item -> new OpenReturnCommand.Item(item.skuId(), item.quantity()))
                .toList();
        return ApiResponse.ok(returnUseCase.open(new OpenReturnCommand(
                orderNo, userId, items, request.reason(), request.reasonDetail())));
    }

    @GetMapping("/api/v1/returns")
    @Operation(summary = "我的退貨單", description = "新到舊")
    public ApiResponse<List<ReturnRequestView>> listForUser(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser Long userId) {
        return ApiResponse.ok(returnUseCase.listForUser(userId, page, size));
    }

    @GetMapping("/api/v1/returns/{returnNo}")
    @Operation(summary = "查詢退貨單", description = "只能查自己的")
    public ApiResponse<ReturnRequestView> findForUser(@PathVariable String returnNo,
                                                      @CurrentUser Long userId) {
        return ApiResponse.ok(returnUseCase.findForUser(returnNo, userId));
    }

    @PostMapping("/api/v1/returns/{returnNo}/cancel")
    @Operation(summary = "撤回退貨申請", description = "貨已被收下後不能再撤")
    public ApiResponse<ReturnRequestView> cancel(@PathVariable String returnNo,
                                                 @CurrentUser Long userId) {
        return ApiResponse.ok(returnUseCase.cancel(returnNo, userId));
    }

    // ---- 客服 ----

    @GetMapping("/api/v1/admin/returns")
    @Operation(summary = "待審退貨清單", description = "依狀態篩選，建立時間由舊到新")
    public ApiResponse<List<ReturnRequestView>> listByStatus(
            @RequestParam(defaultValue = "REQUESTED") ReturnStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(returnUseCase.listByStatus(status, limit));
    }

    @PostMapping("/api/v1/admin/returns/{returnNo}/approve")
    @Operation(summary = "核准退貨")
    public ApiResponse<ReturnRequestView> approve(@PathVariable String returnNo,
                                                  @Valid @RequestBody ReturnReviewRequest request) {
        return ApiResponse.ok(returnUseCase.approve(returnNo, request.note()));
    }

    @PostMapping("/api/v1/admin/returns/{returnNo}/reject")
    @Operation(summary = "駁回退貨", description = "必須說明理由")
    public ApiResponse<ReturnRequestView> reject(@PathVariable String returnNo,
                                                 @Valid @RequestBody ReturnReviewRequest request) {
        return ApiResponse.ok(returnUseCase.reject(returnNo, request.note()));
    }

    @PostMapping("/api/v1/admin/returns/{returnNo}/receive")
    @Operation(summary = "驗收退回品",
            description = "逐行判定是否可再售；漏掉任何一行會被拒絕")
    public ApiResponse<ReturnRequestView> receive(@PathVariable String returnNo,
                                                  @Valid @RequestBody ReturnReceiveRequest request) {
        return ApiResponse.ok(returnUseCase.receive(returnNo, request.restockDecisions()));
    }

    @PostMapping("/api/v1/admin/returns/{returnNo}/refund")
    @Operation(summary = "送出退款",
            description = "扣減可退額度並寫入 outbox；實際金流與庫存回補由消費端執行")
    public ApiResponse<ReturnRequestView> refund(@PathVariable String returnNo) {
        return ApiResponse.ok(returnUseCase.refund(returnNo));
    }
}
