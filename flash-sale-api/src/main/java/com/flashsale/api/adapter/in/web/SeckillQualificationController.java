package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.SeckillQualificationUseCase;
import com.flashsale.application.port.in.SeckillQualificationUseCase.Challenge;
import com.flashsale.application.port.in.SeckillQualificationUseCase.Qualification;
import com.flashsale.application.port.in.SeckillQualificationUseCase.QualifyCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 開賣前的資格預檢。 */
@RestController
@RequestMapping("/api/v1/seckill")
@Tag(name = "搶購資格", description = "開賣前領取資格憑證")
public class SeckillQualificationController {

    private static final String DEVICE_HEADER = "X-Device-Id";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_DEVICE_ID_LENGTH = 64;

    private final SeckillQualificationUseCase qualification;

    public SeckillQualificationController(SeckillQualificationUseCase qualification) {
        this.qualification = qualification;
    }

    @GetMapping("/challenge")
    @Operation(summary = "領一道驗證題", description = "無狀態：題目與到期時間簽在 token 裡")
    public ApiResponse<Challenge> challenge() {
        return ApiResponse.ok(qualification.issueChallenge());
    }

    @PostMapping("/activities/{activityId}/qualify")
    @Operation(summary = "取得搶購資格", description = "身分、黑名單、風險評分、驗證題都在這裡做完；熱路徑只驗憑證")
    public ApiResponse<Qualification> qualify(@CurrentUser Long userId,
                                              @PathVariable Long activityId,
                                              @Valid @RequestBody QualifyRequest request,
                                              @RequestHeader(value = DEVICE_HEADER, required = false) String deviceId,
                                              HttpServletRequest http) {
        return ApiResponse.ok(qualification.qualify(new QualifyCommand(
                userId, activityId, clientIp(http), truncate(deviceId),
                request.challengeToken(), request.answer())));
    }

    /** 反向代理後面取第一跳。沒有代理時 X-Forwarded-For 可被呼叫端自填——它只是風險訊號之一，不是身分。 */
    private static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }

    private static String truncate(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        String trimmed = deviceId.trim();
        return trimmed.length() > MAX_DEVICE_ID_LENGTH ? trimmed.substring(0, MAX_DEVICE_ID_LENGTH) : trimmed;
    }

    public record QualifyRequest(
            @NotBlank(message = "challengeToken 不可為空") @Size(max = 256) String challengeToken,
            @NotBlank(message = "answer 不可為空") @Size(max = 8) String answer) {
    }
}
