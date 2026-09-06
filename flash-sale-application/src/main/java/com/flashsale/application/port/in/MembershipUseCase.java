package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ExchangeableCouponView;
import com.flashsale.application.port.in.dto.MemberProfileView;
import com.flashsale.application.port.in.dto.PointTransactionView;

import java.math.BigDecimal;
import java.util.List;

/** 會員積分與等級（ADR-0016）。 */
public interface MembershipUseCase {

    /** 會員中心的主資料：等級、積分、升級進度。 */
    MemberProfileView profile(Long userId);

    /** 積分流水，新到舊。 */
    List<PointTransactionView> transactions(Long userId, int page, int size);

    /** 目前開放兌換的券，並標好使用者換不換得起。 */
    List<ExchangeableCouponView> exchangeableCoupons(Long userId);

    /** 訂單完成入帳。 */
    long awardForOrder(Long userId, String orderNo, BigDecimal paidAmount);

    /** 退款扣回。 */
    long clawbackForReturn(Long userId, String orderNo, String returnNo,
                           BigDecimal refundAmount, BigDecimal orderTotal);

    /** 用積分兌換優惠券。 */
    ExchangeResult exchangeForCoupon(Long userId, Long promotionId);

    /** @param couponCode 兌換出來的券號，前端要顯示給使用者 */
    record ExchangeResult(String couponCode, String promotionName, long pointsSpent,
                          long balanceAfter) {
    }
}
