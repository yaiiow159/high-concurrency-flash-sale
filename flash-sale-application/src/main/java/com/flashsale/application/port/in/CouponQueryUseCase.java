package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ClaimableCouponView;
import com.flashsale.application.port.in.dto.CouponView;

import java.util.List;

/** 查使用者手上還能用的券。 */
public interface CouponQueryUseCase {

    /** 還沒用、也還沒過期的券。 */
    List<CouponView> myUsableCoupons(Long userId);

    /** 領券中心：進行中、可以領的券。 */
    List<ClaimableCouponView> claimable(Long userId);

    /** 領一張券。 */
    boolean claim(Long userId, Long promotionId);
}
