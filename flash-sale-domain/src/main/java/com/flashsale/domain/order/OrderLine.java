package com.flashsale.domain.order;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 訂單行。 */
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

    /** 這一次退貨該退多少錢。 */
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
