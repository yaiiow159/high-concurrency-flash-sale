package com.flashsale.application.port.in.command;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Objects;

/**
 * 搶購請求命令。
 *
 * <p>{@code requestId} 由前端在按下按鈕前產生（例如 UUID），是整條鏈路的冪等鍵：
 * Redis 扣減、MQ 消費、庫存補償三處都以它判重。使用者手滑連點兩次只會扣一次庫存。
 */
public record SeckillCommand(
        Long activityId,
        Long userId,
        int quantity,
        String requestId
) {

    public SeckillCommand {
        Objects.requireNonNull(activityId, "activityId 不可為 null");
        Objects.requireNonNull(userId, "userId 不可為 null");
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "購買數量必須大於 0");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "requestId 不可為空");
        }
    }
}
