package com.flashsale.application.port.in.command;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Objects;

/** 搶購請求命令。qualificationToken 是開賣前領到的資格憑證，可為 null——要不要驗由設定決定。 */
public record SeckillCommand(
        Long activityId,
        Long userId,
        int quantity,
        String requestId,
        String qualificationToken
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

    /** 沒有資格憑證的命令：測試與關掉資格檢查時用。 */
    public SeckillCommand(Long activityId, Long userId, int quantity, String requestId) {
        this(activityId, userId, quantity, requestId, null);
    }
}
