package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ClaimableCouponView;
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

    /**
     * 領券中心：進行中、可以領的券。
     *
     * <p>已經領過的<b>仍然回傳</b>，只是標記 {@code claimed}——
     * 從清單裡拿掉會讓使用者以為活動結束了。
     */
    List<ClaimableCouponView> claimable(Long userId);

    /**
     * 領一張券。
     *
     * <p><b>一人一張，由唯一索引保證而不是先查再寫。</b>
     * 先查再寫是 read-modify-write：兩個並行的領取請求都會通過檢查，
     * 然後各發一張。這是這個專案第六次用同一個手法
     * （庫存扣減、券核銷、評分聚合、積分兌換、銷量計入）。
     *
     * @return 這次真的領到了才回 {@code true}；已經領過回 {@code false}
     */
    boolean claim(Long userId, Long promotionId);
}
