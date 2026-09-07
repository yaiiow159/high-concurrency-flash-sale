package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.RiskAdminUseCase;
import com.flashsale.application.port.in.dto.BlacklistView;
import com.flashsale.application.port.in.dto.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 後台風控：黑名單。 */
@RestController
@RequestMapping("/api/v1/admin/risk/blacklist")
@Tag(name = "風控", description = "秒殺黑名單")
public class RiskAdminController {

    private final RiskAdminUseCase risk;

    public RiskAdminController(RiskAdminUseCase risk) {
        this.risk = risk;
    }

    @GetMapping
    @Operation(summary = "黑名單", description = "含已過期的，新到舊")
    public ApiResponse<PageView<BlacklistView>> list(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(risk.list(page, size));
    }

    @PostMapping
    @Operation(summary = "列入黑名單", description = "已在名單上會覆寫原因與到期時間")
    public ApiResponse<BlacklistView> add(@CurrentUser Long operatorUserId,
                                          @Valid @RequestBody AddRequest request) {
        return ApiResponse.ok(risk.add(request.userId(), request.reason(), request.expiresAt(), operatorUserId));
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "移出黑名單")
    public ApiResponse<Void> remove(@PathVariable Long userId) {
        risk.remove(userId);
        return ApiResponse.ok(null);
    }

    public record AddRequest(
            @NotNull(message = "userId 不可為空") Long userId,
            @NotBlank(message = "原因不可為空") @Size(max = 200, message = "原因不可超過 200 字") String reason,
            Instant expiresAt) {
    }
}
