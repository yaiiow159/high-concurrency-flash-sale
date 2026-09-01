package com.flashsale.application.port.in.command;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

/** 登入命令。 */
public record LoginCommand(String email, String rawPassword) {

    public LoginCommand {
        if (email == null || email.isBlank() || rawPassword == null || rawPassword.isEmpty()) {
            // 這裡回「帳號或密碼錯誤」而非「參數不合法」，維持與驗證失敗一致的回應，
            // 不讓攻擊者從錯誤碼差異推斷任何事。
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
    }
}
