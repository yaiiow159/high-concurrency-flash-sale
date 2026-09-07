package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.PromotionAdminUseCase;
import com.flashsale.application.port.in.dto.PageView;
import com.flashsale.application.port.in.dto.PromotionAdminView;
import com.flashsale.application.port.in.dto.PromotionCommand;
import com.flashsale.domain.promotion.DiscountType;
import com.flashsale.domain.promotion.PromotionRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/** 後台優惠管理。 */
@RestController
@RequestMapping("/api/v1/admin/promotions")
@Tag(name = "優惠管理", description = "建立、修改、啟停用優惠規則")
public class PromotionAdminController {

    private final PromotionAdminUseCase promotions;

    public PromotionAdminController(PromotionAdminUseCase promotions) {
        this.promotions = promotions;
    }

    @GetMapping
    @Operation(summary = "優惠列表", description = "含各優惠發券與核銷數")
    public ApiResponse<PageView<PromotionAdminView>> list(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(promotions.list(type, page, size));
    }

    @PostMapping
    @Operation(summary = "建立優惠")
    public ApiResponse<PromotionAdminView> create(@Valid @RequestBody PromotionRequest request) {
        return ApiResponse.ok(promotions.create(request.toCommand()));
    }

    @PutMapping("/{promotionId}")
    @Operation(summary = "修改優惠", description = "type 建立後不可改")
    public ApiResponse<PromotionAdminView> update(@PathVariable Long promotionId,
                                                  @Valid @RequestBody PromotionRequest request) {
        return ApiResponse.ok(promotions.update(promotionId, request.toCommand()));
    }

    @PostMapping("/{promotionId}/enable")
    @Operation(summary = "啟用")
    public ApiResponse<PromotionAdminView> enable(@PathVariable Long promotionId) {
        return ApiResponse.ok(promotions.setEnabled(promotionId, true));
    }

    @PostMapping("/{promotionId}/disable")
    @Operation(summary = "停用", description = "已發出的券即刻不可用")
    public ApiResponse<PromotionAdminView> disable(@PathVariable Long promotionId) {
        return ApiResponse.ok(promotions.setEnabled(promotionId, false));
    }

    /** 金額與比例的合法性交給領域物件驗；這裡只擋格式。 */
    public record PromotionRequest(
            @NotBlank(message = "名稱不可為空") @Size(max = 128, message = "名稱不可超過 128 字") String name,
            @NotNull(message = "type 不可為空") DiscountType type,
            @NotNull(message = "rule 不可為空") PromotionRule rule,
            BigDecimal threshold,
            @NotNull(message = "value 不可為空") BigDecimal value,
            BigDecimal maxDiscount,
            Long pointCost,
            @NotNull(message = "startAt 不可為空") Instant startAt,
            @NotNull(message = "endAt 不可為空") Instant endAt,
            Boolean enabled) {

        PromotionCommand toCommand() {
            return new PromotionCommand(name, type, rule, threshold, value, maxDiscount,
                    pointCost, startAt, endAt, enabled == null || enabled);
        }
    }
}
