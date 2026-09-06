package com.flashsale.domain.order;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.regex.Pattern;

/** 訂單編號值物件。 */
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
