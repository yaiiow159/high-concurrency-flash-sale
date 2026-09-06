package com.flashsale.domain.identity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

/** 電子郵件值物件，同時是帳號識別。 */
public record Email(String value) {

    private static final Pattern SHAPE = Pattern.compile("^[^\\s@]+@[^\\s@.]+\\.[^\\s@]+$");
    private static final int MAX_LENGTH = 254;

    public Email {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "電子郵件不可為空");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "電子郵件過長");
        }
        if (!SHAPE.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "電子郵件格式不合法");
        }
    }

    public static Email of(String value) {
        return new Email(value);
    }

    /** 遮蔽後的顯示形式，供日誌使用。 */
    public String masked() {
        int at = value.indexOf('@');
        String local = value.substring(0, at);
        String domain = value.substring(at);
        if (local.length() <= 2) {
            return "*".repeat(local.length()) + domain;
        }
        return local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1) + domain;
    }

    @Override
    public String toString() {
        // 刻意讓 toString 也是遮蔽的：避免有人不小心把整個物件塞進日誌樣板。
        return masked();
    }
}
