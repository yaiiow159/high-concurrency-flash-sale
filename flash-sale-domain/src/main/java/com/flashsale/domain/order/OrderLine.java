package com.flashsale.domain.order;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 訂單行。
 *
 * <p><b>{@code skuSnapshot} 與 {@code unitPrice} 是快照，不是引用。</b>
 * 商家調價或改商品名之後，歷史訂單不能跟著變——那是財務問題，不是顯示問題。
 * 客訴時使用者說「我買的時候寫 990」，系統必須拿得出當時的數字。
 *
 * <p>同時保留 {@code skuId}：快照回答「當時買了什麼」，
 * ID 回答「那是哪一個商品」。兩者缺一，前者無法追溯，後者無法還原。
 *
 * @param sourceActivityId 此行來自哪一個秒殺活動；一般下單為 {@code null}。
 *                         放在行而非訂單上，是因為「這一件商品是在活動中買的」
 *                         才是準確的語意——未來購物車若容許混入一件秒殺商品，
 *                         這個模型不需要改
 * @param allocatedAmount  這一行<b>實際付了多少</b>——整單折扣分攤下來的結果。
 *                         沒有折扣時等於 {@link #subtotal()}。
 *                         <p><b>它存在的唯一理由是退款。</b> 退款必須按「當初收了多少」退，
 *                         而整單折扣是折在訂單上、退貨卻是退一行。用 {@code subtotal()}
 *                         退一張有折扣的訂單，退的錢會比收的多——而且部分退貨時
 *                         總額還在付款金額的上限之內，那道防線攔不住它。
 *                         <p>存下來而非每次重算：折扣規則會改，而分攤是當時算的
 *                         （與 {@code unitPrice} 快照同一個道理）
 */
public record OrderLine(
        Long skuId,
        String skuSnapshot,
        BigDecimal unitPrice,
        int quantity,
        Long sourceActivityId,
        BigDecimal allocatedAmount
) {

    private static final int SCALE = 2;

    /** 沒有折扣的行：實付就是小計。 */
    public OrderLine(Long skuId, String skuSnapshot, BigDecimal unitPrice,
                     int quantity, Long sourceActivityId) {
        this(skuId, skuSnapshot, unitPrice, quantity, sourceActivityId,
                unitPrice == null || quantity <= 0
                        ? unitPrice
                        : unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    public OrderLine {
        Objects.requireNonNull(skuId, "skuId 不可為 null");
        if (skuSnapshot == null || skuSnapshot.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "商品快照不可為空");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "單價不可為負數");
        }
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "訂單行數量必須大於 0");
        }
        if (allocatedAmount == null || allocatedAmount.signum() < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "實付分攤金額不可為負數");
        }
        if (allocatedAmount.compareTo(unitPrice.multiply(BigDecimal.valueOf(quantity))) > 0) {
            // 分攤只會讓行變便宜。比原價高代表分攤算錯了，
            // 而算錯的方向是「退得比收的多」——擋在這裡，不要讓它變成一筆退款
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "實付分攤金額不可高於原始小計");
        }
    }

    /** 換上分攤後的實付金額，其餘不動。 */
    public OrderLine withAllocatedAmount(BigDecimal allocated) {
        return new OrderLine(skuId, skuSnapshot, unitPrice, quantity, sourceActivityId, allocated);
    }

    /**
     * 這一次退貨該退多少錢。
     *
     * <h2>用累計分攤，不是「單價 × 數量」</h2>
     *
     * <p>有折扣時每一件的實付價幾乎必然除不盡：一行 3 件實付 100.00，
     * 每件 33.33，退三次只退得回 99.99。逐次捨去會讓每一行都漏掉幾分錢，
     * 而那些幾分錢加起來就是對不平的帳。
     *
     * <p>因此算的是<b>兩個累計值的差</b>：
     *
     * <pre>
     *   本次退款 = ⌊實付 × (已退 + 本次) / 總數⌋ − ⌊實付 × 已退 / 總數⌋
     * </pre>
     *
     * <p>全部退完時累計值就是 {@code allocatedAmount} 本身，一分不差。
     * 餘數自然落在最後一次退貨上，不需要額外記錄「已經退了多少錢」。
     *
     * @param returnedBefore 這一行先前已退的件數
     * @param returningNow   本次要退的件數
     */
    public BigDecimal refundFor(int returnedBefore, int returningNow) {
        if (returningNow <= 0 || returnedBefore < 0
                || returnedBefore + returningNow > quantity) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "退貨件數超出這一行的可退範圍");
        }
        return cumulativeRefund(returnedBefore + returningNow)
                .subtract(cumulativeRefund(returnedBefore));
    }

    private BigDecimal cumulativeRefund(int units) {
        return allocatedAmount
                .multiply(BigDecimal.valueOf(units))
                .divide(BigDecimal.valueOf(quantity), SCALE, RoundingMode.DOWN);
    }

    /** 小計。由單價與數量推導，不獨立儲存——避免出現兩個真實來源。 */
    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public boolean isFromActivity(Long activityId) {
        return Objects.equals(sourceActivityId, activityId);
    }
}
