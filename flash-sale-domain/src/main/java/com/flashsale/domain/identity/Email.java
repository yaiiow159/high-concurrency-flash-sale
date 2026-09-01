package com.flashsale.domain.identity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 電子郵件值物件，同時是帳號識別。
 *
 * <p><b>正規化為小寫</b>是關鍵行為，不只是美化：
 * {@code Alice@Example.com} 與 {@code alice@example.com} 是同一個信箱，
 * 若不正規化就存進資料庫，唯一索引擋不住重複註冊——
 * 使用者會發現自己「明明註冊過卻登不進去」，因為登入時大小寫打得不一樣。
 *
 * <p>驗證規則刻意寬鬆：只擋明顯不合法的格式。
 * 嚴格的 RFC 5322 正規表示式冗長難讀，且會誤殺合法信箱；
 * 真正確認信箱可用的方式是寄一封驗證信，不是正規表示式。
 */
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

    /**
     * 遮蔽後的顯示形式，供日誌使用。
     *
     * <p>完整信箱是個資，不該直接寫進日誌——日誌會被收集、轉發、長期保存，
     * 而且存取權限通常比資料庫寬鬆得多。
     */
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
