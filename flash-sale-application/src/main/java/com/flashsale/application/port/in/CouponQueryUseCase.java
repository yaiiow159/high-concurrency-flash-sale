package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.CouponView;

import java.util.List;

/** 查使用者手上還能用的券。 */
public interface CouponQueryUseCase {

    /**
     * 還沒用、也還沒過期的券。
     *
     * <p>只回可用的，不回已使用與已過期的：結帳頁要的是「我現在能選什麼」。
     * 券的歷史屬於另一個問題，需要時另開端點——把兩者混在一起，
     * 前端就得自己過濾，而那個過濾邏輯遲早會與後端的判斷不一致。
     */
    List<CouponView> myUsableCoupons(Long userId);
}
