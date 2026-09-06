package com.flashsale.domain.promotion;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 優惠計算引擎（ADR-0013）。 */
public final class PricingEngine {

    /** 金額一律算到分。 */
    private static final int SCALE = 2;

    private PricingEngine() {
    }

    /** 計算一組品項可以套用哪些優惠。 */
    public static PricingResult calculate(List<PricedItem> items,
                                          List<Promotion> promotions,
                                          Instant now) {
        BigDecimal subtotal = sumOf(items);
        if (subtotal.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "訂單金額必須大於 0");
        }

        // 秒殺品項不參與任何優惠（ADR-0013 決策 4）。
        //
        // 判準是 sourceActivityId 而不是 order.channel()——那是「這一行的價格
        // 從哪裡來」，比「這張訂單是誰建的」精確。用 channel 判斷會讓通道差異
        // 滲進共用邏輯，而那是 ADR-0006 明文禁止的。
        boolean hasSeckillItem = items.stream().anyMatch(PricedItem::isFromSeckill);
        if (hasSeckillItem) {
            return PricingResult.noDiscount(subtotal);
        }

        List<AppliedDiscount> applied = new ArrayList<>();
        BigDecimal running = subtotal;

        // 依 DiscountType 的宣告順序逐級套用。每一級都對「上一級之後的金額」計算，
        // 這正是順序會改變結果的原因
        for (DiscountType type : DiscountType.values()) {
            // 運費折抵**不在這裡**。這個迴圈算的是商品金額，
            // 而運費是訂單層級的另一筆錢（ADR-0019 決策 1）。
            // 讓 SHIPPING 進來的話，一張「滿 2000 免運」會直接把商品折成免費
            if (type == DiscountType.SHIPPING) {
                continue;
            }
            for (Promotion promotion : promotions) {
                if (promotion.type() != type || !promotion.isApplicableAt(now)) {
                    continue;
                }
                BigDecimal discount = promotion.discountFor(running);
                if (discount.signum() <= 0) {
                    continue;
                }
                // 折扣不可超過剩餘金額——否則訂單會變成負數，
                // 而負數金額會一路流進付款與退款
                discount = discount.min(running);
                applied.add(new AppliedDiscount(type, promotion.id(), promotion.name(), discount));
                running = running.subtract(discount);
            }
        }

        return new PricingResult(subtotal, applied, running, allocate(items, subtotal, running));
    }

    /** 運費折抵。 */
    public static AppliedDiscount shippingDiscount(BigDecimal shippingFee,
                                                   BigDecimal goodsPayable,
                                                   List<Promotion> promotions,
                                                   Instant now) {
        if (shippingFee == null || shippingFee.signum() <= 0) {
            return null;
        }
        for (Promotion promotion : promotions) {
            if (promotion.type() != DiscountType.SHIPPING || !promotion.isApplicableAt(now)) {
                continue;
            }
            BigDecimal discount = promotion.discountFor(goodsPayable);
            if (discount.signum() <= 0) {
                continue;
            }
            // 夾在運費本身——免運券折不出比運費更多的錢
            discount = discount.min(shippingFee);
            return new AppliedDiscount(DiscountType.SHIPPING, promotion.id(),
                    promotion.name(), discount);
        }
        return null;
    }

    /** 把折後總額分攤回每一項。 */
    private static List<BigDecimal> allocate(List<PricedItem> items,
                                             BigDecimal subtotal,
                                             BigDecimal payable) {
        List<BigDecimal> allocated = new ArrayList<>(items.size());
        BigDecimal distributed = BigDecimal.ZERO;

        for (PricedItem item : items) {
            BigDecimal share = item.subtotal()
                    .multiply(payable)
                    .divide(subtotal, SCALE, RoundingMode.DOWN);
            allocated.add(share);
            distributed = distributed.add(share);
        }

        BigDecimal remainder = payable.subtract(distributed);
        if (remainder.signum() != 0) {
            int largest = largestItemIndex(items);
            allocated.set(largest, allocated.get(largest).add(remainder));
        }
        return List.copyOf(allocated);
    }

    private static int largestItemIndex(List<PricedItem> items) {
        int largest = 0;
        for (int i = 1; i < items.size(); i++) {
            if (items.get(i).subtotal().compareTo(items.get(largest).subtotal()) > 0) {
                largest = i;
            }
        }
        return largest;
    }

    private static BigDecimal sumOf(List<PricedItem> items) {
        return items.stream()
                .map(PricedItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * 計算結果。
     *
     * @param payable       折後應付
     * @param lineAllocations 每一項分攤到的實付金額，順序與傳入的品項相同。
     * <b>加總必然等於 {@code payable}</b>
     */
    public record PricingResult(
            BigDecimal subtotal,
            List<AppliedDiscount> discounts,
            BigDecimal payable,
            List<BigDecimal> lineAllocations
    ) {

        public static PricingResult noDiscount(BigDecimal subtotal) {
            return new PricingResult(subtotal, List.of(), subtotal, List.of());
        }

        public BigDecimal totalDiscount() {
            return subtotal.subtract(payable);
        }

        /** 依折扣金額由大到小，供畫面呈現——使用者最想先看到折最多的那一筆。 */
        public List<AppliedDiscount> discountsByImpact() {
            return discounts.stream()
                    .sorted(Comparator.comparing(AppliedDiscount::amount).reversed())
                    .toList();
        }
    }
}
