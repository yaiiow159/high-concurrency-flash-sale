package com.flashsale.application.service;

import com.flashsale.application.port.in.MembershipUseCase;
import com.flashsale.application.port.in.dto.ExchangeableCouponView;
import com.flashsale.application.port.in.dto.MemberProfileView;
import com.flashsale.application.port.in.dto.PointTransactionView;
import com.flashsale.application.port.out.MembershipRepository;
import com.flashsale.application.port.out.PromotionRepository;
import com.flashsale.domain.membership.MemberAccount;
import com.flashsale.domain.membership.PointReason;
import com.flashsale.domain.membership.PointTransaction;
import com.flashsale.domain.promotion.Promotion;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 會員積分與等級（ADR-0016）。
 *
 * <h2>這是第一個會被使用者主動嘗試套利的機制</h2>
 *
 * <p>庫存、訂單、退款的錯誤都是「系統做錯了」；積分的錯誤是
 * 「使用者發現了一個可以重複做的動作」。三條會被試的路徑：
 *
 * <ol>
 *   <li>買了拿積分 → 退貨拿回錢 → 積分留著 —— 由 {@link #clawbackForReturn} 擋下</li>
 *   <li>買到升級 → 退貨 → 等級留著 —— 累計消費也一起扣回</li>
 *   <li>同一則完成事件重放兩次 → 積分翻倍 —— 由唯一索引擋下</li>
 * </ol>
 */
@Service
public class MembershipService implements MembershipUseCase {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

    /** 兌換出來的券的有效期。 */
    private static final Duration COUPON_VALIDITY = Duration.ofDays(30);

    private final MembershipRepository membershipRepository;
    private final PromotionRepository promotionRepository;
    private final Clock clock;

    public MembershipService(MembershipRepository membershipRepository,
                             PromotionRepository promotionRepository,
                             Clock clock) {
        this.membershipRepository = membershipRepository;
        this.promotionRepository = promotionRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public MemberProfileView profile(Long userId) {
        return MemberProfileView.from(membershipRepository.findAccount(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PointTransactionView> transactions(Long userId, int page, int size) {
        return membershipRepository.findTransactions(userId, page * size, size).stream()
                .map(PointTransactionView::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExchangeableCouponView> exchangeableCoupons(Long userId) {
        long balance = membershipRepository.findAccount(userId).pointBalance();
        return promotionRepository.findExchangeable(clock.instant()).stream()
                .map(promotion -> ExchangeableCouponView.of(promotion, balance))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>倍率取自<b>入帳當下</b>的等級。這表示這一單的回饋用的是升級前的倍率，
     * 而升級的效果從下一單開始——這是可以有不同答案的商業決策，
     * 但它必須是一個答案：用「入帳後的等級」會讓同一筆消費同時
     * 決定等級又享受那個等級，而那在跨越門檻的那一單上會產生
     * 一個使用者算不出來的數字。
     */
    @Override
    @Transactional
    public long awardForOrder(Long userId, String orderNo, BigDecimal paidAmount) {
        MemberAccount account = membershipRepository.findAccount(userId);
        long points = account.tier().pointsFor(paidAmount);

        // 金額為 0 或負數才真的什麼都不做。
        //
        // **不足一點仍然要寫一列**：一筆 50 元的訂單拿不到點，但它確實是消費，
        // 不計入累計消費的話，一連串小額訂單永遠推不動等級。
        // 而累計消費的冪等鍵就在流水表上，因此那筆訂單必須有一列——
        // 這也是 PointTransaction 允許 delta 為 0 的理由。
        if (paidAmount == null || paidAmount.signum() <= 0) {
            return 0L;
        }

        boolean recorded = membershipRepository.record(userId, points,
                PointReason.ORDER_COMPLETED, orderNo, paidAmount, clock.instant());
        if (!recorded) {
            log.debug("訂單 {} 的積分已入過帳，略過重放", orderNo);
            return 0L;
        }

        log.info("積分入帳 userId={}, orderNo={}, 等級={}, 點數={}, 累計消費 +{}",
                userId, orderNo, account.tier(), points, paidAmount);
        return points;
    }

    /**
     * {@inheritDoc}
     *
     * <p>比例的基準是<b>流水裡那一筆原始入帳</b>，不是重算。
     * 重算會用到當下的等級倍率，而使用者可能在這期間升級了——
     * 於是「退一半的貨」會收回比當初給的還多的積分。
     */
    @Override
    @Transactional
    public long clawbackForReturn(Long userId, String orderNo, String returnNo,
                                  BigDecimal refundAmount, BigDecimal orderTotal) {
        PointTransaction earned = membershipRepository
                .findByReference(userId, PointReason.ORDER_COMPLETED, orderNo)
                .orElse(null);

        // 沒入過帳就沒東西可扣。訂單在送達前被退（此時還沒入帳）會走到這裡，
        // 那是正常路徑而不是異常
        if (earned == null) {
            log.debug("訂單 {} 沒有積分入帳紀錄，不需扣回", orderNo);
            return 0L;
        }

        long clawback = proportionalClawback(earned.delta(), refundAmount, orderTotal);
        if (clawback <= 0) {
            return 0L;
        }

        // 累計消費同樣扣回。不扣的話「買了再退」就能免費升級，
        // 而等級決定倍率——那是可以無限重複的套利
        BigDecimal spendDelta = refundAmount == null ? BigDecimal.ZERO : refundAmount.negate();

        boolean recorded = membershipRepository.record(userId, -clawback,
                PointReason.RETURN_CLAWBACK, returnNo, spendDelta, clock.instant());
        if (!recorded) {
            log.debug("退貨單 {} 的積分已扣回過，略過重放", returnNo);
            return 0L;
        }

        log.info("積分扣回 userId={}, returnNo={}, 原始入帳={}, 扣回={}",
                userId, returnNo, earned.delta(), clawback);
        return clawback;
    }

    /**
     * 按退款比例算要扣回多少點。
     *
     * <p>無條件<b>捨去</b>：扣回的方向對使用者有利，而少扣幾點不會有人客訴，
     * 多扣會。這與積分入帳的捨去方向一致——兩邊都對使用者有利。
     *
     * <p>訂單總額為 0 或不明時退回「全部扣回」：那只會發生在資料異常的情況下，
     * 而此時保守的選擇是不讓積分留下來。
     */
    private static long proportionalClawback(long earnedPoints, BigDecimal refundAmount,
                                             BigDecimal orderTotal) {
        if (earnedPoints <= 0 || refundAmount == null || refundAmount.signum() <= 0) {
            return 0L;
        }
        if (orderTotal == null || orderTotal.signum() <= 0) {
            return earnedPoints;
        }
        // 退款可能因為資料問題而超過訂單金額，夾住比例避免扣回超過當初給的
        BigDecimal ratio = refundAmount.divide(orderTotal, 6, RoundingMode.DOWN)
                .min(BigDecimal.ONE);
        return BigDecimal.valueOf(earnedPoints).multiply(ratio)
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    /**
     * {@inheritDoc}
     *
     * <p>扣點與發券在<b>同一個交易</b>裡。分開做的話：
     * 先扣點後發券則扣了點沒拿到券，先發券後扣點則拿到券卻沒扣點——
     * 而後者是可以無限重複的。
     *
     * <p>扣點走條件式 UPDATE（{@code WHERE point_balance >= cost}），
     * 兩個並行的兌換只有一個能成功。這與庫存扣減完全同形。
     */
    @Override
    @Transactional
    public ExchangeResult exchangeForCoupon(Long userId, Long promotionId) {
        Instant now = clock.instant();
        Promotion promotion = promotionRepository.findPromotionById(promotionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROMOTION_NOT_FOUND,
                        "找不到這個優惠"));

        if (!promotion.isExchangeable() || !promotion.isApplicableAt(now)) {
            throw new BusinessException(ErrorCode.PROMOTION_NOT_EXCHANGEABLE,
                    "「%s」目前不開放兌換".formatted(promotion.name()));
        }

        long cost = promotion.pointCost();
        // 券號要先產生，因為它同時是這筆扣點的來源單號——
        // 用它當冪等鍵讓「同一張券只會被扣一次點」在資料庫層成立
        String code = promotionRepository.issueCoupon(userId, promotionId,
                now.plus(COUPON_VALIDITY));

        if (!membershipRepository.redeem(userId, cost, code, now)) {
            // 點數不足。發券那一步已經執行了，因此**必須讓整個交易回滾**——
            // 拋例外是唯一能保證這件事的方式，回 false 會讓券留下來
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS,
                    "積分不足，兌換「%s」需要 %d 點".formatted(promotion.name(), cost));
        }

        long balanceAfter = membershipRepository.findAccount(userId).pointBalance();
        log.info("積分兌換 userId={}, promotionId={}, 券號={}, 花費={}",
                userId, promotionId, code, cost);
        return new ExchangeResult(code, promotion.name(), cost, balanceAfter);
    }
}
