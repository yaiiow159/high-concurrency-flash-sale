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

    /**
     * 訂單完成入帳。
     *
     * <p>入帳點是「已送達」而不是「已付款」——付款到送達之間訂單還可能被取消，
     * 那段時間發積分只會製造要回收的債。
     *
     * <p><b>冪等</b>：同一張訂單重複呼叫只會入帳一次。
     * 訂單完成事件是至少一次投遞，重放是常態不是異常。
     *
     * @return 這次實際入帳的點數；{@code 0} 代表重放或金額不足一點
     */
    long awardForOrder(Long userId, String orderNo, BigDecimal paidAmount);

    /**
     * 退款扣回。
     *
     * <p>按<b>比例</b>扣回：部分退貨只收回那一部分。比例的基準是流水裡
     * 那一筆原始入帳，不是重算一次——重算會用到當下的等級倍率，
     * 而使用者可能在這期間升級了，於是「退一半的貨」會收回比當初給的還多。
     *
     * <p>不扣回的話「買了再退」就能免費升級，而等級決定倍率——
     * 那是可以無限重複的套利。
     *
     * @param returnNo     退貨單號。同一張訂單可能有多張退貨單，
     *                     因此冪等鍵用它而不是訂單編號
     * @param refundAmount 這次退了多少錢
     * @param orderTotal   訂單的實付總額，用於算比例
     * @return 這次實際扣回的點數（正數）
     */
    long clawbackForReturn(Long userId, String orderNo, String returnNo,
                           BigDecimal refundAmount, BigDecimal orderTotal);

    /**
     * 用積分兌換優惠券。
     *
     * <p>這是積分唯一的用途（ADR-0016 決策 7）。直接折抵訂單會讓退款
     * 變成兩種資產的組合，而現在的退款路徑只認得錢——
     * 做一半的結果是「錢退了、積分沒退」，一個安靜的黑洞。
     *
     * <p>換成券之後這個問題消失了：點數換成券的那一刻，它就是一張券，
     * 之後的行為與其他優惠完全一樣。
     */
    ExchangeResult exchangeForCoupon(Long userId, Long promotionId);

    /** @param couponCode 兌換出來的券號，前端要顯示給使用者 */
    record ExchangeResult(String couponCode, String promotionName, long pointsSpent,
                          long balanceAfter) {
    }
}
