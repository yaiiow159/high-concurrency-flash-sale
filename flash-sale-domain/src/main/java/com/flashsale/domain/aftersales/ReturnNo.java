package com.flashsale.domain.aftersales;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.regex.Pattern;

/**
 * 退貨單號值物件。
 *
 * <p>前綴 {@code RMA-}（Return Merchandise Authorization）。
 * 理由與 {@link com.flashsale.domain.payment.PaymentNo} 相同——
 * 客服工單裡訂單號、付款單號、退貨單號會同時出現，
 * 沒有前綴時三串裸數字沒有人分得出誰是誰。
 */
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
