package com.flashsale.domain.order;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.regex.Pattern;

/**
 * 訂單編號值物件。
 *
 * <p>使用值物件而非裸 {@code String}，可讓編譯器擋下「把 userId 傳成 orderNo」這類錯誤，
 * 並把格式驗證收斂在單一處。
 *
 * <p>訂單號在 <b>請求進來時就先產生並回傳給前端</b>（此時 DB 尚未落庫），
 * 使前端能立刻拿著它輪詢建單結果；同時它也是 MQ 消費端的冪等鍵。
 */
public record OrderNo(String value) {

    private static final Pattern VALID_PATTERN = Pattern.compile("^[0-9A-Za-z_-]{8,64}$");

    public OrderNo {
        if (value == null || !VALID_PATTERN.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "訂單編號格式不合法: " + value);
        }
    }

    public static OrderNo of(String value) {
        return new OrderNo(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
