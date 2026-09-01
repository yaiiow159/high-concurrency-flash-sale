package com.flashsale.application.port.in.command;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

/**
 * 註冊命令。
 *
 * <p>密碼以明文形式短暫存在於此，是不可避免的——雜湊必須在伺服器端做。
 * 前端先雜湊再送並不會提升安全性：那只是讓「前端雜湊值」變成新的密碼，
 * 而傳輸安全本來就該由 TLS 負責。
 */
public record RegisterUserCommand(String email, String rawPassword, String displayName) {

    /** 下限取 8 碼。長度是密碼強度最有效的單一因素，遠勝於強制混用特殊符號。 */
    private static final int MIN_PASSWORD_LENGTH = 8;
    /** BCrypt 只取前 72 個位元組，超過的部分會被靜默忽略——明確擋下以免誤導使用者。 */
    private static final int MAX_PASSWORD_LENGTH = 72;

    public RegisterUserCommand {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "密碼長度至少需 " + MIN_PASSWORD_LENGTH + " 個字元");
        }
        if (rawPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "密碼長度不可超過 " + MAX_PASSWORD_LENGTH + " 個字元");
        }
    }
}
