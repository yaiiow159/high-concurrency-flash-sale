package com.flashsale.domain.aftersales;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.regex.Pattern;

/** 退貨單號值物件。 */
public record ReturnNo(String value) {

    public static final String PREFIX = "RMA-";
    private static final Pattern VALID_PATTERN = Pattern.compile("^RMA-[0-9A-Za-z_-]{8,60}$");

    public ReturnNo {
        if (value == null || !VALID_PATTERN.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "退貨單號格式不合法: " + value);
        }
    }

    public static ReturnNo of(String value) {
        return new ReturnNo(value);
    }

    public static ReturnNo fromId(long id) {
        return new ReturnNo(PREFIX + id);
    }

    @Override
    public String toString() {
        return value;
    }
}
