package com.flashsale.domain.payment;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.regex.Pattern;

/** 付款單號值物件。 */
public record PaymentNo(String value) {

    public static final String PREFIX = "PAY-";
    private static final Pattern VALID_PATTERN = Pattern.compile("^PAY-[0-9A-Za-z_-]{8,60}$");

    public PaymentNo {
        if (value == null || !VALID_PATTERN.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "付款單號格式不合法: " + value);
        }
    }

    public static PaymentNo of(String value) {
        return new PaymentNo(value);
    }

    public static PaymentNo fromId(long id) {
        return new PaymentNo(PREFIX + id);
    }

    @Override
    public String toString() {
        return value;
    }
}
